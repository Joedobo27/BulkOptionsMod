package com.joedobo27.bulkoptions;


import com.wurmonline.server.items.Item;
import com.wurmonline.server.items.ItemTemplate;
import com.wurmonline.server.items.ItemTemplateFactory;
import javassist.*;
import javassist.bytecode.Descriptor;
import javassist.expr.ExprEditor;
import javassist.expr.MethodCall;
import org.gotti.wurmunlimited.modloader.ReflectionUtil;
import org.gotti.wurmunlimited.modloader.classhooks.HookManager;
import org.gotti.wurmunlimited.modloader.interfaces.Configurable;
import org.gotti.wurmunlimited.modloader.interfaces.PreInitable;
import org.gotti.wurmunlimited.modloader.interfaces.ServerStartedListener;
import org.gotti.wurmunlimited.modloader.interfaces.WurmServerMod;
import org.gotti.wurmunlimited.modsupport.actions.ModActions;

import java.lang.reflect.Field;
import java.util.*;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Collectors;

public class BulkOptionsMod implements WurmServerMod, PreInitable, Configurable, ServerStartedListener{

    private static int qualityRange = 10;
    private static final Logger logger = Logger.getLogger(BulkOptionsMod.class.getName());
    private static TempConstants vars = new TempConstants(new int[]{0,0}, false, false, new int[]{0,0,0,0},
            false, new int[]{0,0});

    @Override
    public void configure(Properties properties) {
        qualityRange = Integer.parseInt(properties.getProperty("qualityRange", Integer.toString(qualityRange)));
        vars.setReplenish(Boolean.parseBoolean(properties.getProperty("replenish", Boolean.toString(vars.replenish))));
        vars.setRarityStorage(Boolean.parseBoolean(properties.getProperty("rarityStorage", Boolean.toString(vars.rarityStorage))));
        vars.setPreparedFoodStorage(Boolean.parseBoolean(properties.getProperty("preparedFoodStorage", Boolean.toString(vars.preparedFoodStorage))));
        if (properties.getProperty("makeBulkItems").length() > 0) {
            logger.log(Level.INFO, "makeBulkItems: " + properties.getProperty("makeBulkItems"));
            vars.setMakeItemsBulk(Arrays.stream(properties.getProperty("makeBulkItems").replaceAll("\\s", "").split(","))
                    .mapToInt(Integer::parseInt)
                    .boxed()
                    .collect(Collectors.toCollection(ArrayList::new)));
        }
    }

    @Override
    public void preInit() {
        if (vars.preparedFoodStorage || vars.rarityStorage)
            moveToItemBytecode();
        addBulkItemBytecode();
        addBulkItemToCrateBytecode();
        if (vars.rarityStorage)
            answerBytecode();
        evaluateChangesArray(vars.successesQR, "qualityRange");
        if (vars.rarityStorage)
            evaluateChangesArray(vars.successesRS, "rarityStorage");
        if (vars.preparedFoodStorage)
            evaluateChangesArray(vars.successesPFS, "preparedFoodStorage");
    }

    @Override
    public void onServerStarted() {
        if (vars.replenish) {
            ModActions.registerAction(new ReplenishAction());
            logger.info("Added an action to convert herbs/spice into fresh using water.");
        }
        if (!vars.makeItemsBulk.isEmpty())
            makeItemsBulkReflection();
        vars = null;
    }

    /**
     * insert into RemoveItemQuestion.answer() code to handle making the withdrawn item of rarity.
     * insert before-
     *      if (toInsert.isRepairable()) {...}
     * line 223: 777
     */
    private void answerBytecode(){
        try{
            CtClass RemoveItemQuestionCt = HookManager.getInstance().getClassPool().get("com.wurmonline.server.questions.RemoveItemQuestion");
            CtClass returnType =  CtPrimitiveType.voidType;
            CtClass[] paramTypes = {
                    HookManager.getInstance().getClassPool().get("java.util.Properties")
            };
            CtMethod answerCt = RemoveItemQuestionCt.getMethod("answer", Descriptor.ofMethod(returnType, paramTypes));

            String source = "toInsert.setRarity(bulkitem.getRarity());";
            answerCt.insertAt(223, source);
            vars.successesRS[0] = 1;
        }catch (NotFoundException | CannotCompileException e){
            logger.warning(e.getMessage());
        }
    }

    /**
     * Change Item.moveToItem() inside the if (target.isBulkContainer()) {...} code block so
     *      1)so food items can be placed in bulk. Methods isDish() and usesFoodState() will always be false disabling
     *      the limiting logic statements. line 3346: 5245
     * 2) so rare items can go in bulk; logic, if (this.getRarity() > 0) {...} is always false. line 3290: 4825
     */
    private void moveToItemBytecode(){
        try {
            CtClass itemCt = HookManager.getInstance().getClassPool().get("com.wurmonline.server.items.Item");

            CtClass returnType = CtPrimitiveType.booleanType;
            CtClass[] paramTypes = {
                    HookManager.getInstance().getClassPool().get("com.wurmonline.server.creatures.Creature"),
                    CtPrimitiveType.longType, CtPrimitiveType.booleanType
            };
            CtMethod moveToItemCt = itemCt.getMethod("moveToItem", Descriptor.ofMethod(returnType, paramTypes));

            moveToItemCt.instrument(new ExprEditor(){
                @Override
                public void edit(MethodCall methodCall) throws CannotCompileException {
                    if (Objects.equals("isDish", methodCall.getMethodName()) && vars.preparedFoodStorage){
                        logger.fine("replace on isDish inside Item.moveToItem() at line " + methodCall.getLineNumber());
                        methodCall.replace("$_ = false;");
                        vars.successesPFS[0] = 1;
                    } else if (Objects.equals("usesFoodState", methodCall.getMethodName()) && methodCall.getLineNumber() == 3346 &&
                            vars.preparedFoodStorage){
                        logger.fine("replace on usesFoodState inside Item.moveToItem() at line " + methodCall.getLineNumber());
                        methodCall.replace("$_ = false;");
                        vars.successesPFS[1] = 1;
                    } else if (Objects.equals("getRarity", methodCall.getMethodName()) && methodCall.getLineNumber() == 3290 &&
                            vars.rarityStorage){
                        logger.fine("replace on getRarity inside Item.moveToItem() at line " + methodCall.getLineNumber());
                        methodCall.replace("$_ = 0;");
                        vars.successesRS[1] = 1;
                    }
                }
            });
        }catch (NotFoundException | CannotCompileException e){
            logger.warning(e.getMessage());
        }
    }

    /**
     * Change Item.AddBulkItem 1)so instead of calling getItemWithTemplateAndMaterial() to find matching items in a bulk container
     * it will call this mod's hook method, getTargetToAdd().
     * 2) Insert a statement that will make new rarity bulk entries.
     *      insert before and within the code block that makes new items-
     *          float percent2 = 1.0f;
     *          if (!this.isFish()) {...}
     *      line 3965: 485
     */
    private void addBulkItemBytecode(){
        try {
            CtClass itemCt = HookManager.getInstance().getClassPool().get("com.wurmonline.server.items.Item");
            CtClass returnType = CtPrimitiveType.booleanType;
            CtClass[] paramTypes = {
                    HookManager.getInstance().getClassPool().get("com.wurmonline.server.creatures.Creature"),
                    HookManager.getInstance().getClassPool().get("com.wurmonline.server.items.Item")
            };
            CtMethod addBulkItemCt = itemCt.getMethod("AddBulkItem", Descriptor.ofMethod(returnType, paramTypes));
            addBulkItemCt.instrument(new ExprEditor(){
                @Override
                public void edit(MethodCall methodCall) throws CannotCompileException {
                    if (Objects.equals("getItemWithTemplateAndMaterial", methodCall.getMethodName())){
                        logger.fine("replace on getItemWithTemplateAndMaterial inside Item.AddBulkItem() at line "
                                + methodCall.getLineNumber());
                        methodCall.replace("$_ = com.joedobo27.bulkoptions.BulkOptionsMod.getTargetToAdd(target, this, this.getMaterial(), auxToCheck, this.getRealTemplateId());");
                        vars.successesQR[0] = 1;
                    }
                }
            });
            if (vars.rarityStorage) {
                String source = "toaddTo.setRarity(this.getRarity());";
                addBulkItemCt.insertAt(3965, source);
                vars.successesRS[2] = 1;
            }

        }catch (NotFoundException | CannotCompileException e){
            logger.warning(e.getMessage());
        }
    }

    /**
     * Change Item.AddBulkItemToCrate 1)so instead of calling getItemWithTemplateAndMaterial() to find matching items in a bulk container
     * it will call this mod's hook method, getTargetToAdd().
     * 2) Insert a statement that will make new rarity bulk entries.
     *      insert before and within the code block that makes new items-
     *          float percent2 = 1.0f;
     *          if (!this.isFish()) {...}
     *      line 3838: 657
     */
    private void addBulkItemToCrateBytecode(){
        try {
            CtClass itemCt = HookManager.getInstance().getClassPool().get("com.wurmonline.server.items.Item");
            CtClass returnType = CtPrimitiveType.booleanType;
            CtClass[] paramTypes = {
                    HookManager.getInstance().getClassPool().get("com.wurmonline.server.creatures.Creature"),
                    HookManager.getInstance().getClassPool().get("com.wurmonline.server.items.Item")
            };
            CtMethod addBulkItemCt = itemCt.getMethod("AddBulkItemToCrate", Descriptor.ofMethod(returnType, paramTypes));
            addBulkItemCt.instrument(new ExprEditor(){
                @Override
                public void edit(MethodCall methodCall) throws CannotCompileException {
                    if (Objects.equals("getItemWithTemplateAndMaterial", methodCall.getMethodName())){
                        logger.fine("replace on getItemWithTemplateAndMaterial inside Item.AddBulkItemToCrate() at line "
                                + methodCall.getLineNumber());
                        methodCall.replace("$_ = com.joedobo27.bulkoptions.BulkOptionsMod.getTargetToAdd(target, this, this.getMaterial(), auxToCheck, this.getRealTemplateId());");
                        vars.successesQR[1] = 1;
                    }
                }
            });
            if (vars.rarityStorage){
                String source = "toaddTo.setRarity(this.getRarity());";
                addBulkItemCt.insertAt(3838, source);
                vars.successesRS[3] = 1;
            }

        }catch (NotFoundException | CannotCompileException e){
            logger.warning(e.getMessage());
        }
    }

    /**
     * Custom hooking method that identifies matching entries inside bulk containers.
     * required matching states are:
     * 1. same item template id, see items.ItemList.class
     * 2. same material, see items.Materials.convertMaterialStringIntoByte()
     * 3. same AuxData value, in this case used for food cooked/prepared states. See Constant field references in Item.class
     * 4. same RealTemplateId, what template id the bulk item is. It's either -10 or a templateId value.
     * 5. same quality grouping. This can be configured to be any range: 2, 5, 10, 20.
     * 6. same rarity, 0 through 3 for common, rare, supreme, fantastic.
     *
     * @param bulkContainer Item WU object type
     * @param depositItem Item WU object type
     * @param depositMaterial int primitive type
     * @param depositAuxByte byte primitive type
     * @param depositRealTemplateId int primitive type
     * @return Item WU object  type
     */
    @SuppressWarnings("unused")
    public static Item getTargetToAdd(Item bulkContainer, Item depositItem, int depositMaterial, byte depositAuxByte, int depositRealTemplateId){
        return Arrays.stream(bulkContainer.getItemsAsArray())
                .filter(item -> item.getRealTemplateId() == depositItem.getTemplateId())
                .filter(item -> item.getMaterial() == depositMaterial)
                .filter(item -> item.getAuxData() == depositAuxByte)
                .filter(item -> (depositRealTemplateId == -10 && item.getData1() == -1) || item.getData1() == depositRealTemplateId)
                .filter(item -> isLowerBoundGTEQ(item.getQualityLevel(), depositItem.getQualityLevel()))
                .filter(item -> isUpperBoundLT(item.getQualityLevel(), depositItem.getQualityLevel()))
                .filter(item -> item.getRarity() == depositItem.getRarity())
                .findFirst()
                .orElse(null);
    }

    private static boolean isLowerBoundGTEQ(double bulkItemQl, double depositItemQl){
        int intPart = (int) Math.floor(bulkItemQl / qualityRange);
        return depositItemQl >= intPart * qualityRange;
    }

    private static boolean isUpperBoundLT(double bulkItemQl, double depositItemQl){
        int intPart = (int) Math.floor(bulkItemQl / qualityRange) + 1;
        return depositItemQl < intPart * qualityRange;
    }

    private static void makeItemsBulkReflection() {
        final int[] successCount = {0};
        ArrayList<Integer> makeItemsBulk = vars.getMakeItemsBulk();
        Arrays.stream(ItemTemplateFactory.getInstance().getTemplates())
                .forEach(itemTemplate -> {
                    if(makeItemsBulk.stream()
                            .filter(value -> Objects.equals(value, itemTemplate.getTemplateId()))
                            .count() > 0){
                        if(setFieldBulk(itemTemplate))
                            successCount[0]++;
                    }
                });
        if (successCount[0] != makeItemsBulk.size())
            logger.info("Make items bulk count specified, actual done: " + makeItemsBulk.size() + ", " + successCount[0]);
        else
            logger.info("Make items bulk SUCCESS. TemplateId's " + makeItemsBulk.toString());
    }

    private static boolean setFieldBulk(ItemTemplate itemTemplate){
        try {
            Field fieldBulk = ReflectionUtil.getField(ItemTemplate.class, "bulk");
            ReflectionUtil.setPrivateField(itemTemplate, fieldBulk, Boolean.TRUE);
        }catch (IllegalAccessException | NoSuchFieldException e){
            logger.fine(e.getMessage());
            return false;
        }
        return true;
    }

    @SuppressWarnings("unused")
    private static void evaluateChangesArray(int[] ints, String option) {
        boolean changesSuccessful = Arrays.stream(ints).noneMatch(value -> value == 0);
        if (changesSuccessful) {
            logger.log(Level.INFO, option + " option changes SUCCESS");
        } else {
            logger.log(Level.INFO, option + " option changes FAILURE");
            logger.log(Level.FINE, Arrays.toString(ints));
        }
    }
}