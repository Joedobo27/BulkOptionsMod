package com.joedobo27.bom;


import com.wurmonline.server.Server;
import com.wurmonline.server.behaviours.Action;
import com.wurmonline.server.behaviours.ActionEntry;
import com.wurmonline.server.behaviours.Actions;
import com.wurmonline.server.creatures.Creature;
import com.wurmonline.server.items.Item;
import com.wurmonline.server.items.ItemList;
import com.wurmonline.server.skills.Skill;
import com.wurmonline.server.skills.SkillList;
import org.gotti.wurmunlimited.modsupport.actions.ActionPerformer;
import org.gotti.wurmunlimited.modsupport.actions.BehaviourProvider;
import org.gotti.wurmunlimited.modsupport.actions.ModAction;
import org.gotti.wurmunlimited.modsupport.actions.ModActions;

import java.util.Collections;
import java.util.List;

import static org.gotti.wurmunlimited.modsupport.actions.ActionPropagation.*;
import static org.gotti.wurmunlimited.modsupport.actions.ActionPropagation.CONTINUE_ACTION;


public class ReplenishActionPerformer implements ModAction, BehaviourProvider, ActionPerformer {

    private final int actionId;
    private final ActionEntry actionEntry;

    private ReplenishActionPerformer(int actionId, ActionEntry actionEntry){
        this.actionId = actionId;
        this.actionEntry = actionEntry;
    }

    private static class SingletonHelper {
        private static final ReplenishActionPerformer _performer;
        static {
            int replenishEntryId = ModActions.getNextActionId();
            _performer = new ReplenishActionPerformer( replenishEntryId,
                    ActionEntry.createEntry((short)replenishEntryId, "Replenish", "replenishing", new int[]{}));
        }
    }

    @Override
    public short getActionId(){
        return (short)actionId;
    }

    ActionEntry getActionEntry() {
        return actionEntry;
    }

    @Override
    public List<ActionEntry> getBehavioursFor(Creature performer, Item source, Item target){
        if (!ConfigureOptions.getInstance().isEnableReplenish() ||
                source == null || source.getTemplateId() != ItemList.water ||
                (!target.isHerb() && !target.isSpice()) || target.isFresh())
            return BehaviourProvider.super.getBehavioursFor(performer, source, target);
        return Collections.singletonList(actionEntry);
    }

    @Override
    public boolean action(Action action, Creature performer, Item active, Item target, short aActionId, float counter) {
        if (!ConfigureOptions.getInstance().isEnableReplenish())
            return propagate(action, SERVER_PROPAGATION, ACTION_PERFORMER_PROPAGATION);
        if (target == null) {
            performer.getCommunicator().sendNormalServerMessage("You need a target to make fresh.");
            return propagate(action, FINISH_ACTION, NO_SERVER_PROPAGATION, NO_ACTION_PERFORMER_PROPAGATION);
        }
        boolean isHerbOrSpice = target.isHerb() || target.isSpice();
        if (!isHerbOrSpice) {
            performer.getCommunicator().sendNormalServerMessage("You can only refresh a herb or spice.");
            return propagate(action, FINISH_ACTION, NO_SERVER_PROPAGATION, NO_ACTION_PERFORMER_PROPAGATION);
        }
        boolean isFresh = target.isFresh();
        if (isFresh) {
            performer.getCommunicator().sendNormalServerMessage(
                    String.format("The %s is already fresh.", target.getName()));
            return propagate(action, FINISH_ACTION, NO_SERVER_PROPAGATION, NO_ACTION_PERFORMER_PROPAGATION);
        }
        if (active == null){
            performer.getCommunicator().sendNormalServerMessage(
                    String.format("You need to use water to refresh up the %s.", target.getName()));
            return propagate(action, FINISH_ACTION, NO_SERVER_PROPAGATION, NO_ACTION_PERFORMER_PROPAGATION);
        }
        boolean isWaterActive = active.getTemplateId() == ItemList.water;
        if (!isWaterActive) {
            performer.getCommunicator().sendNormalServerMessage(
                    String.format("You need to water use to refresh the %s.", target.getName()));
            return propagate(action, FINISH_ACTION, NO_SERVER_PROPAGATION, NO_ACTION_PERFORMER_PROPAGATION);
        }

        Skill cooking = performer.getSkills().getSkillOrLearn(SkillList.GROUP_COOKING);
        int time;
        if (counter == 1.0f) {
            time = Actions.getStandardActionTime(performer, cooking, active, 0.0d);
            action.setTimeLeft(time);
            performer.getCommunicator().sendNormalServerMessage("You start to dredge.");
            Server.getInstance().broadCastAction(performer.getName() + " starts to dredge.", performer, 5);
            performer.sendActionControl(Actions.actionEntrys[362].getVerbString(), true, time);
            performer.getStatus().modifyStamina(-3000.0f);
            return propagate(action, CONTINUE_ACTION, NO_SERVER_PROPAGATION, NO_ACTION_PERFORMER_PROPAGATION);
        }

        time = action.getTimeLeft();
        if (counter * 10.0f > time) {
            return propagate(action, CONTINUE_ACTION, NO_SERVER_PROPAGATION, NO_ACTION_PERFORMER_PROPAGATION);
        }

        target.setIsFresh(true);
        target.updateName();
        performer.getCommunicator().sendNormalServerMessage("You dig a hole.");
        Server.getInstance().broadCastAction(performer.getName() + " digs a hole.", performer, 5);
        return propagate(action, FINISH_ACTION, NO_SERVER_PROPAGATION, NO_ACTION_PERFORMER_PROPAGATION);
    }

    static ReplenishActionPerformer getReplenishActionPerformer() {
        return SingletonHelper._performer;
    }
}
