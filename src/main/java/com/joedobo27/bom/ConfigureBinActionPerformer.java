package com.joedobo27.bom;

import com.wurmonline.server.behaviours.Action;
import com.wurmonline.server.behaviours.ActionEntry;
import com.wurmonline.server.creatures.Creature;
import com.wurmonline.server.items.Item;
import com.wurmonline.server.items.ItemList;
import com.wurmonline.server.questions.Question;
import org.gotti.wurmunlimited.modsupport.actions.ActionPerformer;
import org.gotti.wurmunlimited.modsupport.actions.BehaviourProvider;
import org.gotti.wurmunlimited.modsupport.actions.ModAction;
import org.gotti.wurmunlimited.modsupport.actions.ModActions;
import org.gotti.wurmunlimited.modsupport.questions.ModQuestions;

import java.util.Collections;
import java.util.List;

import static org.gotti.wurmunlimited.modsupport.actions.ActionPropagation.*;

public class ConfigureBinActionPerformer implements ModAction, BehaviourProvider, ActionPerformer {

    private final int actionId;
    private final ActionEntry actionEntry;

    ConfigureBinActionPerformer(int actionId, ActionEntry actionEntry) {
        this.actionId = actionId;
        this.actionEntry = actionEntry;
    }

    private static class SingletonHelper {
        private static final ConfigureBinActionPerformer _performer;

        static {
            int configureBinId = ModActions.getNextActionId();
            _performer = new ConfigureBinActionPerformer(configureBinId,
                    ActionEntry.createEntry((short) configureBinId, "Configure", "configuring", new int[]{}));
        }
    }

    @Override
    public short getActionId() {
        return (short) actionId;
    }

    public ActionEntry getActionEntry() {
        return actionEntry;
    }

    @Override
    public List<ActionEntry> getBehavioursFor(Creature performer, Item active, Item target) {
        if (active.getTemplateId() != ItemList.bodyHand || !target.isBulkContainer()) {
            return BehaviourProvider.super.getBehavioursFor(performer, active, target);
        }
        return Collections.singletonList(this.actionEntry);
    }

    @Override
    public boolean action(Action action, Creature performer, Item active, Item target, short aActionId, float counter) {
        if (active.getTemplateId() != ItemList.bodyHand || !target.isBulkContainer())
            return propagate(action);
        ConfigureBinQuestion configureBinQuestion = new ConfigureBinQuestion(target);
        Question question = ModQuestions.createQuestion(performer, "Configure bulk",
                "Configure this how?", target.getWurmId(), configureBinQuestion);
        configureBinQuestion.sendQuestion(question);
        return propagate(action, FINISH_ACTION);
    }

    static ConfigureBinActionPerformer getConfigureBinActionPerformer() {
        return ConfigureBinActionPerformer.SingletonHelper._performer;
    }
}
