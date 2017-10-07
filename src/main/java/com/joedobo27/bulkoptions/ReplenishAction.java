package com.joedobo27.bulkoptions;

import com.joedobo27.libs.action.ActionMaster;
import com.wurmonline.server.behaviours.Action;
import com.wurmonline.server.creatures.Creature;
import com.wurmonline.server.items.Item;
import org.jetbrains.annotations.Nullable;

import java.util.WeakHashMap;

class ReplenishAction extends ActionMaster {

    private static WeakHashMap<Action, ReplenishAction> actionDataWeakHashMap = new WeakHashMap<>();

    ReplenishAction(Action action, Creature performer, @Nullable Item activeTool, @Nullable Integer usedSkill,
                    int minSkill, int maxSkill, int longestTime, int shortestTime, int minimumStamina){
        super(action, performer, activeTool, usedSkill, minSkill, maxSkill, longestTime, shortestTime, minimumStamina);
    }

    static boolean hashMapContainsKey(Action action) {
        return actionDataWeakHashMap.containsKey(action);
    }

    @Override
    public Item getActiveTool() {
        return this.activeTool;
    }

    static WeakHashMap<Action, ReplenishAction> getActionDataWeakHashMap() {
        return actionDataWeakHashMap;
    }
}
