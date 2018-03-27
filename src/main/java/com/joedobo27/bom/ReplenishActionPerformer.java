package com.joedobo27.bom;


import com.wurmonline.server.behaviours.Action;
import com.wurmonline.server.behaviours.ActionEntry;
import com.wurmonline.server.creatures.Creature;
import com.wurmonline.server.items.Item;
import com.wurmonline.server.items.ItemList;
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

    ReplenishActionPerformer(int actionId, ActionEntry actionEntry){
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

    public ActionEntry getActionEntry() {
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

        ReplenishAction replenishAction = ReplenishAction.getReplenishAction(action);
        if (replenishAction == null){
            ConfigureOptions.ActionOptions options = ConfigureOptions.getInstance().getReplenishAction();
            replenishAction = new ReplenishAction(action, performer, active, SkillList.GROUP_COOKING, options.getMinSkill(),
                    options.getMaxSkill(), options.getLongestTime(), options.getShortestTime(), options.getMinimumStamina(),
                    target);
        }

        if (replenishAction.hasAFailureCondition() && replenishAction.isActionStartTime(counter))
            return propagate(action, FINISH_ACTION, NO_SERVER_PROPAGATION, NO_ACTION_PERFORMER_PROPAGATION);
        if (replenishAction.isActionStartTime(counter)) {
            replenishAction.doActionStartMessages();
            replenishAction.setInitialTime(this.actionEntry);
            performer.getStatus().modifyStamina(-1000.0f);
            return propagate(action, CONTINUE_ACTION, NO_SERVER_PROPAGATION, NO_ACTION_PERFORMER_PROPAGATION);
        }

        if (!replenishAction.isActionTimedOut(action, counter)) {
            return propagate(action, CONTINUE_ACTION, NO_SERVER_PROPAGATION, NO_ACTION_PERFORMER_PROPAGATION);
        }
        if(replenishAction.hasAFailureCondition())
            return propagate(action, FINISH_ACTION, NO_SERVER_PROPAGATION, NO_ACTION_PERFORMER_PROPAGATION);

        target.setIsFresh(true);
        target.updateName();
        replenishAction.doActionEndMessages();
        return propagate(action, FINISH_ACTION, NO_SERVER_PROPAGATION, NO_ACTION_PERFORMER_PROPAGATION);
    }

    static ReplenishActionPerformer getReplenishActionPerformer() {
        return SingletonHelper._performer;
    }
}
