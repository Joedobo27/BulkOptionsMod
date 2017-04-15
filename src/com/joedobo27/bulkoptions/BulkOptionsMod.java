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
    private static boolean replenish = false;
    private static boolean rarityStorage = false;
    private static boolean preparedFoodStorage = false;
    private static final Logger logger = Logger.getLogger(BulkOptionsMod.class.getName());
    private static ArrayList<Integer> makeItemsBulk = new ArrayList<>();
    private static final String[] STEAM_VERSION = new String[]{"1.3.1.3"};
    private static boolean versionCompliant = false;

    @Override
    public void configure(Properties properties) {
        qualityRange = Integer.parseInt(properties.getProperty("qualityRange", Integer.toString(qualityRange)));
        replenish = Boolean.parseBoolean(properties.getProperty("replenish", Boolean.toString(replenish)));
        rarityStorage = Boolean.parseBoolean(properties.getProperty("rarityStorage", Boolean.toString(rarityStorage)));
        preparedFoodStorage = Boolean.parseBoolean(properties.getProperty("preparedFoodStorage", Boolean.toString(preparedFoodStorage)));
        if (properties.getProperty("makeBulkItems").length() > 0) {
            logger.log(Level.INFO, "makeBulkItems: " + properties.getProperty("makeBulkItems"));
            makeItemsBulk = Arrays.stream(properties.getProperty("makeBulkItems").replaceAll("\\s", "").split(","))
                    .mapToInt(Integer::parseInt)
                    .boxed()
                    .collect(Collectors.toCollection(ArrayList::new));
        }

        if (Arrays.stream(STEAM_VERSION)
                .filter(s -> Objects.equals(s, properties.getProperty("steamVersion", null)))
                .count() > 0)
            versionCompliant = true;
        else
            logger.log(Level.WARNING, "WU version mismatch. Your " + properties.getProperty(" steamVersion", null)
                    + "version doesn't match one of BulkOptionsMod's required versions " + Arrays.toString(STEAM_VERSION));
    }

    @Override
    public void preInit() {
        if (!versionCompliant)
            return;
        if (preparedFoodStorage || rarityStorage)
            moveToItemBytecode();
        addBulkItemBytecode();
        addBulkItemToCrateBytecode();
        if (rarityStorage)
            answerBytecode();
    }

    @Override
    public void onServerStarted() {
        if (!versionCompliant)
            return;
        if (replenish)
            ModActions.registerAction(new ReplenishAction());
        if (!makeItemsBulk.isEmpty())
            makeItemsBulkReflection();
    }

    /**
     * insert into RemoveItemQuestion.answer() code to handle making the withdrawn item of rarity.
     * insert before-
     *      if (toInsert.isRepairable()) {...}
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
        }catch (NotFoundException | CannotCompileException e){
            logger.log(Level.WARNING, e.getMessage(), e);
        }
    }

    /**
     * Change Item.moveToItem() 1)so food items can be placed in bulk. Methods isDish() and usesFoodState() will always be false disabling
     * the limiting logic statements.
     * 2) so rare items can go in bulk; logic, if (this.getRarity() > 0) {...} is always false.
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
                    if (Objects.equals("isDish", methodCall.getMethodName()) && preparedFoodStorage){
                        logger.info("replace on isDish inside Item.moveToItem() at line " + methodCall.getLineNumber());
                        methodCall.replace("$_ = false;");
                    } else if (Objects.equals("usesFoodState", methodCall.getMethodName()) && methodCall.getLineNumber() == 3275 &&
                            preparedFoodStorage){
                        logger.info("replace on usesFoodState inside Item.moveToItem() at line " + methodCall.getLineNumber());
                        methodCall.replace("$_ = false;");
                    } else if (Objects.equals("getRarity", methodCall.getMethodName()) && methodCall.getLineNumber() == 3219 &&
                            rarityStorage){
                        logger.info("replace on getRarity inside Item.moveToItem() at line " + methodCall.getLineNumber());
                        methodCall.replace("$_ = 0;");
                    }
                }
            });
        }catch (NotFoundException | CannotCompileException e){
            logger.log(Level.WARNING, e.getMessage(), e);
        }
    }

    /**
     * Change Item.AddBulkItem 1)so instead of calling getItemWithTemplateAndMaterial() to find matching items in a bulk container
     * it will call this mod's hook method, getTargetToAdd().
     * 2) Insert a statement that will make new rarity bulk entries.
     * insert before-
     *      float percent2 = 1.0f;
     *      if (!this.isFish()) {...}
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
                        logger.info("replace on getItemWithTemplateAndMaterial inside Item.AddBulkItem() at line "
                                + methodCall.getLineNumber());
                        methodCall.replace("$_ = com.joedobo27.bulkoptions.BulkOptionsMod.getTargetToAdd(target, this, this.getMaterial(), auxToCheck, this.getRealTemplateId());");
                    }
                }
            });
            String source = "toaddTo.setRarity(this.getRarity());";
            addBulkItemCt.insertAt(3894, source);

        }catch (NotFoundException | CannotCompileException e){
            logger.log(Level.WARNING, e.getMessage(), e);
        }
    }

    /**
     * Change Item.AddBulkItemToCrate 1)so instead of calling getItemWithTemplateAndMaterial() to find matching items in a bulk container
     * it will call this mod's hook method, getTargetToAdd().
     * 2) Insert a statement that will make new rarity bulk entries.
     * insert before-
     *      float percent2 = 1.0f;
     *      if (!this.isFish()) {...}
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
                        logger.info("replace on getItemWithTemplateAndMaterial inside Item.AddBulkItemToCrate() at line "
                                + methodCall.getLineNumber());
                        methodCall.replace("$_ = com.joedobo27.bulkoptions.BulkOptionsMod.getTargetToAdd(target, this, this.getMaterial(), auxToCheck, this.getRealTemplateId());");
                    }
                }
            });
            String source = "toaddTo.setRarity(this.getRarity());";
            addBulkItemCt.insertAt(3680, source);

        }catch (NotFoundException | CannotCompileException e){
            logger.log(Level.WARNING, e.getMessage(), e);
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
        Arrays.stream(ItemTemplateFactory.getInstance().getTemplates())
                .forEach(itemTemplate -> {
                    if(makeItemsBulk.stream()
                            .filter(value -> Objects.equals(value, itemTemplate.getTemplateId()))
                            .count() > 0){
                        setFieldBulk(itemTemplate);
                    }
                });
        logger.info("ItemIds set to bulk: " + makeItemsBulk.toString());
    }

    private static void setFieldBulk(ItemTemplate itemTemplate){
        try {
            Field fieldBulk = ReflectionUtil.getField(ItemTemplate.class, "bulk");
            ReflectionUtil.setPrivateField(itemTemplate, fieldBulk, Boolean.TRUE);
        }catch (IllegalAccessException | NoSuchFieldException ignored){}
    }

}