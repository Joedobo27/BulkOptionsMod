package com.joedobo27.bulkoptions;

import com.wurmonline.server.Server;
import com.wurmonline.server.Servers;
import com.wurmonline.server.behaviours.Action;
import com.wurmonline.server.behaviours.ActionEntry;
import com.wurmonline.server.behaviours.Actions;
import com.wurmonline.server.creatures.Creature;
import com.wurmonline.server.items.Item;
import com.wurmonline.server.items.ItemList;
import com.wurmonline.server.players.Player;
import com.wurmonline.server.skills.SkillList;
import org.gotti.wurmunlimited.modsupport.actions.ActionPerformer;
import org.gotti.wurmunlimited.modsupport.actions.BehaviourProvider;
import org.gotti.wurmunlimited.modsupport.actions.ModAction;
import org.gotti.wurmunlimited.modsupport.actions.ModActions;

import java.util.Collections;
import java.util.List;
import java.util.logging.Logger;


public class ReplenishAction implements ModAction, BehaviourProvider, ActionPerformer {
    private static final Logger logger = Logger.getLogger(BulkOptionsMod.class.getName());
    private final short actionId;
    private final ActionEntry actionEntry;

    ReplenishAction(){
        actionId = (short) ModActions.getNextActionId();
        actionEntry = ActionEntry.createEntry(actionId, "Replenish", "replenishing", new int[]{});
        ModActions.registerAction(actionEntry);
    }

    @Override
    public short getActionId(){
        return actionId;
    }

    @Override
    public List<ActionEntry> getBehavioursFor(Creature performer, Item source, Item target){
        if (performer instanceof Player && source != null && source.getTemplateId() == ItemList.water &&
                (target.isHerb() || target.isSpice()) && !target.isFresh()){
            return Collections.singletonList(actionEntry);
        }else {
            return null;
        }
    }

    @Override
    public boolean action(Action action, Creature performer, Item source, Item target, short aActionId, float counter) {
        if (source != null && source.getTemplateId() == ItemList.water && target != null && (target.isHerb() || target.isSpice())) {
            int time;
            String youMessage;
            String broadcastMessage;
            if (counter == 1.0f) {
                if (target.isFresh()){
                    youMessage = String.format("You can't replenish a fresh item.");
                    performer.getCommunicator().sendNormalServerMessage(youMessage);
                    return true;
                }
                youMessage = String.format("You start %s.", action.getActionEntry().getVerbString());
                broadcastMessage = String.format("%s starts to %s.", performer.getName(), action.getActionString());
                performer.getCommunicator().sendNormalServerMessage(youMessage);
                Server.getInstance().broadCastAction(broadcastMessage, performer, 5);
                time = Actions.getQuickActionTime(performer, performer.getSkills().getSkillOrLearn(SkillList.GROUP_COOKING),
                        source, 50);
                // there is a about a 3 second default time that isn't accelerated with faster server action times.
                time = Math.max(1, time - (int)Math.floor(30.0 / Servers.localServer.getActionTimer()));

                action.setTimeLeft(time);
                performer.sendActionControl(action.getActionEntry().getVerbString(), true, time);
                return false;
            } else if (counter > action.getTimeLeft() / 10.0f) {
                target.setIsFresh(true);
                target.updateName();
                youMessage = String.format("You finish %s.", action.getActionEntry().getVerbString());
                broadcastMessage = String.format("%s finishes %s.", performer.getName(), action.getActionString());
                performer.getCommunicator().sendNormalServerMessage(youMessage);
                Server.getInstance().broadCastAction(broadcastMessage, performer, 5);
                return true;
            }
        }
        return false;
    }
}
