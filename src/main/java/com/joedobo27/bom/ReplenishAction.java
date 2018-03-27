package com.joedobo27.bom;

import com.joedobo27.libs.action.ActionMaster;
import com.wurmonline.math.TilePos;
import com.wurmonline.server.behaviours.Action;
import com.wurmonline.server.creatures.Creature;
import com.wurmonline.server.items.Item;
import com.wurmonline.server.items.ItemList;
import org.jetbrains.annotations.Nullable;

import java.util.WeakHashMap;

class ReplenishAction extends ActionMaster {

    private final Item targetItem;
    private static WeakHashMap<Action, ReplenishAction> performers = new WeakHashMap<>();

    ReplenishAction(Action action, Creature performer, @Nullable Item activeTool, @Nullable Integer usedSkill,
                    int minSkill, int maxSkill, int longestTime, int shortestTime, int minimumStamina, Item targetItem){
        super(action, performer, activeTool, usedSkill, minSkill, maxSkill, longestTime, shortestTime, minimumStamina);
        this.targetItem = targetItem;
        performers.put(action, this);
    }

    @Nullable
    static ReplenishAction getReplenishAction(Action action) {
        if (!performers.containsKey(action))
            return null;
        return performers.get(action);
    }

    boolean hasAFailureCondition() {
        if (targetItem == null) {
            performer.getCommunicator().sendNormalServerMessage("You need a target to make fresh.");
            return true;
        }
        boolean isHerbOrSpice = targetItem.isHerb() || targetItem.isSpice();
        if (!isHerbOrSpice) {
            performer.getCommunicator().sendNormalServerMessage("You can only refresh a herb or spice.");
            return true;
        }
        boolean isFresh = targetItem.isFresh();
        if (isFresh) {
            performer.getCommunicator().sendNormalServerMessage(
                    String.format("The %s is already fresh.", targetItem.getName()));
            return true;
        }
        if (activeTool == null){
            performer.getCommunicator().sendNormalServerMessage(
                    String.format("You need to use water to refresh up the %s.", targetItem.getName()));
            return true;
        }
        boolean isWaterActive = activeTool.getTemplateId() == ItemList.water;
        if (!isWaterActive) {
            performer.getCommunicator().sendNormalServerMessage(
                    String.format("You need to water use to refresh the %s.", targetItem.getName()));
            return true;
        }
        return false;
    }

    @Override
    public Item getActiveTool() {
        return this.activeTool;
    }

    @Override
    public Item getTargetItem() {
        return null;
    }

    @Override
    public TilePos getTargetTile() {
        return null;
    }
}
