package com.joedobo27.bulkoptions;


import com.wurmonline.server.behaviours.ActionEntry;
import com.wurmonline.server.creatures.Communicator;
import com.wurmonline.server.items.Item;
import com.wurmonline.server.items.ItemTemplate;
import com.wurmonline.server.items.ItemTemplateFactory;
import com.wurmonline.server.players.Titles;
import javassist.*;
import javassist.bytecode.Descriptor;
import javassist.expr.ExprEditor;
import javassist.expr.MethodCall;
import org.gotti.wurmunlimited.modloader.ReflectionUtil;
import org.gotti.wurmunlimited.modloader.classhooks.HookManager;
import org.gotti.wurmunlimited.modloader.interfaces.*;
import org.gotti.wurmunlimited.modsupport.actions.ModActions;

import java.lang.reflect.Field;
import java.util.*;
import java.util.logging.Level;
import java.util.logging.Logger;

public class BulkOptionsMod implements WurmServerMod, PreInitable, Configurable, ServerStartedListener, PlayerMessageListener {

    static final Logger logger = Logger.getLogger(BulkOptionsMod.class.getName());

    @Override public boolean onPlayerMessage(Communicator communicator, String s) {
        return false;
    }

    @Override
    public MessagePolicy onPlayerMessage(Communicator communicator, String message, String title) {
        if (communicator.getPlayer().getPower() == 5 && message.startsWith("/BulkOptionsMod properties")) {
            communicator.getPlayer().getCommunicator().sendNormalServerMessage(
                    "Reloading properties for BulkOptionsMod."
            );
            ConfigureOptions.resetOptions();
            return MessagePolicy.DISCARD;
        }
        return MessagePolicy.PASS;
    }

    @Override
    public void configure(Properties properties) {
        ConfigureOptions.setOptions(properties);
    }

    @Override
    public void preInit() {
        int[] result;
        int[] successes = new int[8];
        Arrays.fill(successes, 0);
        ConfigureOptions options = ConfigureOptions.getInstance();

        injectItemSortingMethods();

        result = addBulkItemBytecode();
        System.arraycopy(result,0, successes, 0, 2);
        result = addBulkItemToCrateBytecode();
        System.arraycopy(result,0, successes, 2, 2);
        if (options.isEnableRarityStorage()) {
            result = answerBytecode();
            System.arraycopy(result,0, successes, 4, 1);
        }
        if (options.isEnablePreparedFoodStorage() || options.isEnableRarityStorage()) {
            result = moveToItemBytecode();
            System.arraycopy(result,0, successes, 5, 3);
        }
        evaluateChangesArray(successes, "BulkOptionsMod");
    }

    @Override
    public void onServerStarted() {
        for (Titles.Title title : Titles. Title.values()) {
            logger.info(title.toString());
        }

        ConfigureOptions options = ConfigureOptions.getInstance();
        if (options.isEnableReplenish()) {
            int replenishEntryId = ModActions.getNextActionId();
            ActionEntry actionEntryReplenish = ActionEntry.createEntry((short)replenishEntryId, "Replenish",
                    "replenishing", new int[]{});
            ModActions.registerAction(actionEntryReplenish);
            ReplenishActionPerformer replenishActionPerformer = new ReplenishActionPerformer(replenishEntryId,
                    actionEntryReplenish);
            ModActions.registerAction(replenishActionPerformer);
            logger.info("Added Replenish action to convert herbs/spice into fresh using water.");
        }
        if (options.getMakeTheseItemsBulk() != null)
            makeItemsBulkReflection();
    }

    private void injectItemSortingMethods(){
        try {
            CtClass ctClassItem = HookManager.getInstance().getClassPool().get("com.wurmonline.server.items.Item");
            HookManager.getInstance().getClassPool().importPackage("com.wurmonline.server.items.Item");
            CtMethod ctMethodGetTargetToAdd = CtNewMethod.make(
                "static Item getTargetToAdd(Item bulkContainer, Item depositItem, int depositMaterial, byte depositAuxByte, int depositRealTemplateId){"+
                "Item itemMatch = null;"+
                "Item[] bulkItems = bulkContainer.getItemsAsArray();"+
                "for (int i=0; i<bulkItems.length;i++) {"+
                    "if (bulkItems[i].getRealTemplateId() != depositItem.getTemplateId()){continue;}"+
                    "if (bulkItems[i].getMaterial() != depositMaterial){continue;}"+
                    "if (bulkItems[i].getAuxData() != depositAuxByte){continue;}"+
                    "if ((depositRealTemplateId != -10 || bulkItems[i].getData1() != -1) && bulkItems[i].getData1() != depositRealTemplateId){continue;}"+
                    "if (bulkItems[i].getRarity() != depositItem.getRarity()){continue;}"+
                    "int intPartLower = (int) Math.floor((double)(bulkItems[i].getQualityLevel() / "+ConfigureOptions.getInstance().getQualityRange()+"));"+
                    "boolean isLowerBoundGTEQ = depositItem.getQualityLevel() >= intPartLower * "+ConfigureOptions.getInstance().getQualityRange()+";"+
                    "int intPartUpper = (int) Math.floor((double)(bulkItems[i].getQualityLevel() / "+ConfigureOptions.getInstance().getQualityRange()+")) + 1;"+
                    "boolean isUpperBoundLT = depositItem.getQualityLevel() < intPartUpper * "+ConfigureOptions.getInstance().getQualityRange()+";"+
                    "if (!isLowerBoundGTEQ || !isUpperBoundLT){continue;}"+
                    "itemMatch = bulkItems[i];"+
                    "break;}"+
                "return itemMatch;}"
                    , ctClassItem);
            ctClassItem.addMethod(ctMethodGetTargetToAdd);
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
    private int[] addBulkItemBytecode(){
        int[] successes = new int[2];
        Arrays.fill(successes, 0);
        ConfigureOptions options = ConfigureOptions.getInstance();
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
                        methodCall.replace("$_ = com.wurmonline.server.items.Item.getTargetToAdd(target, this, this.getMaterial(), auxToCheck, this.getRealTemplateId());");
                        successes[0] = 1;
                    }
                }
            });
            if (options.isEnableRarityStorage()) {
                String source = "toaddTo.setRarity(this.getRarity());";
                addBulkItemCt.insertAt(3965, source);
                successes[1] = 1;
            }

        }catch (NotFoundException | CannotCompileException e){
            logger.warning(e.getMessage());
        }
        return successes;
    }

    /**
     * Change Item.AddBulkItemToCrate
     *      1)so instead of calling getItemWithTemplateAndMaterial() to find matching items
     *          in a bulk container it will call this mod's hook method, getTargetToAdd().
     *      2) Insert a statement that will make new rarity bulk entries.
     *          insert before and within the code block that makes new items-
     *
     *          float percent2 = 1.0f;
     *          if (!this.isFish()) {...}
     *      line 3838: 657
     */
    private int[] addBulkItemToCrateBytecode(){
        ConfigureOptions options = ConfigureOptions.getInstance();
        int[] successes = new int[2];
        Arrays.fill(successes, 0);
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
                        methodCall.replace("$_ = com.wurmonline.server.items.Item.getTargetToAdd(target, this, this.getMaterial(), auxToCheck, this.getRealTemplateId());");
                        successes[0] = 1;
                    }
                }
            });
            if (options.isEnableRarityStorage()){
                String source = "toaddTo.setRarity(this.getRarity());";
                addBulkItemCt.insertAt(3838, source);
                successes[1] = 1;
            }

        }catch (NotFoundException | CannotCompileException e){
            logger.warning(e.getMessage());
        }
        return successes;
    }

    /**
     * insert into RemoveItemQuestion.answer() code to handle making the withdrawn item of rarity.
     * insert before-
     *      if (toInsert.isRepairable()) {...}
     * line 223: 777
     */
    private int[] answerBytecode(){
        int[] successes = new int[1];
        Arrays.fill(successes, 0);
        try{
            CtClass RemoveItemQuestionCt = HookManager.getInstance().getClassPool().get(
                    "com.wurmonline.server.questions.RemoveItemQuestion");
            CtClass returnType =  CtPrimitiveType.voidType;
            CtClass[] paramTypes = {
                    HookManager.getInstance().getClassPool().get("java.util.Properties")
            };
            CtMethod answerCt = RemoveItemQuestionCt.getMethod("answer", Descriptor.ofMethod(returnType, paramTypes));

            String source = "toInsert.setRarity(bulkitem.getRarity());";
            answerCt.insertAt(223, source);
            successes[0] = 1;
        }catch (NotFoundException | CannotCompileException e){
            logger.warning(e.getMessage());
        }
        return successes;
    }

    /**
     * Change Item.moveToItem() inside the if (target.isBulkContainer()) {...} code block so
     *      1)so food items can be placed in bulk. Methods isDish() and usesFoodState() will always be false disabling
     *      the limiting logic statements. line 3402: 5419
     * 2) so rare items can go in bulk; logic, if (this.getRarity() > 0) {...} is always false. line 3345: 4988
     */
    private int[] moveToItemBytecode(){
        ConfigureOptions options = ConfigureOptions.getInstance();
        int[] successes = new int[3];
        Arrays.fill(successes, 0);
        try {
            CtClass ctClassItem = HookManager.getInstance().getClassPool().get("com.wurmonline.server.items.Item");

            CtClass returnType = CtPrimitiveType.booleanType;
            CtClass[] paramTypes = {
                    HookManager.getInstance().getClassPool().get("com.wurmonline.server.creatures.Creature"),
                    CtPrimitiveType.longType, CtPrimitiveType.booleanType
            };
            CtMethod ctMethodMoveToItem = ctClassItem.getMethod("moveToItem", Descriptor.ofMethod(returnType, paramTypes));

            ctMethodMoveToItem.instrument(new ExprEditor(){
                @Override
                public void edit(MethodCall methodCall) throws CannotCompileException {
                    if (Objects.equals("isDish", methodCall.getMethodName()) && options.isEnablePreparedFoodStorage()){
                        logger.fine("replace on isDish inside Item.moveToItem() at line " + methodCall.getLineNumber());
                        methodCall.replace("$_ = false;");
                        successes[0] = 1;
                    } else if (Objects.equals("usesFoodState", methodCall.getMethodName()) && methodCall.getLineNumber() == 3402 &&
                            options.isEnablePreparedFoodStorage()){
                        logger.fine("replace on usesFoodState inside Item.moveToItem() at line " + methodCall.getLineNumber());
                        methodCall.replace("$_ = false;");
                        successes[1] = 1;
                    } else if (Objects.equals("getRarity", methodCall.getMethodName()) && methodCall.getLineNumber() == 3345 &&
                            options.isEnableRarityStorage()){
                        logger.fine("replace on getRarity inside Item.moveToItem() at line " + methodCall.getLineNumber());
                        methodCall.replace("$_ = 0;");
                        successes[2] = 1;
                    }
                }
            });
        }catch (NotFoundException | CannotCompileException e){
            logger.warning(e.getMessage());
        }
        return successes;
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
    public static Item getTargetToAdd1(Item bulkContainer, Item depositItem, int depositMaterial, byte depositAuxByte, int depositRealTemplateId){
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

    public static Item getTargetToAdd(Item bulkContainer, Item depositItem, int depositMaterial, byte depositAuxByte, int depositRealTemplateId){
        Item itemMatch = null;
        Item[] bulkItems = bulkContainer.getItemsAsArray();
        for (int i=0; i < bulkItems.length; i++) {
            if (bulkItems[i].getRealTemplateId() != depositItem.getTemplateId())
                continue;
            if (bulkItems[i].getMaterial() != depositMaterial)
                continue;
            if (bulkItems[i].getAuxData() != depositAuxByte)
                continue;
            if ((depositRealTemplateId != -10 || bulkItems[i].getData1() != -1) && bulkItems[i].getData1() != depositRealTemplateId)
                continue;
            if (bulkItems[i].getRarity() != depositItem.getRarity())
                continue;
            int intPartLower = (int) Math.floor((double)bulkItems[i].getQualityLevel() / ConfigureOptions.getInstance().getQualityRange());
            boolean isLowerBoundGTEQ = depositItem.getQualityLevel() >= intPartLower * ConfigureOptions.getInstance().getQualityRange();
            int intPartUpper = (int) Math.floor((double)bulkItems[i].getQualityLevel() / ConfigureOptions.getInstance().getQualityRange()) + 1;
            boolean isUpperBoundLT = depositItem.getQualityLevel() < intPartUpper * ConfigureOptions.getInstance().getQualityRange();
            if (!isLowerBoundGTEQ || !isUpperBoundLT)
                continue;
            itemMatch = bulkItems[i];
            break;
        }
        return itemMatch;
    }

    private static boolean isLowerBoundGTEQ(double bulkItemQl, double depositItemQl){
        ConfigureOptions options = ConfigureOptions.getInstance();
        int intPart = (int) Math.floor(bulkItemQl / options.getQualityRange());
        return depositItemQl >= intPart * options.getQualityRange();
    }

    private static boolean isUpperBoundLT(double bulkItemQl, double depositItemQl){
        ConfigureOptions options = ConfigureOptions.getInstance();
        int intPart = (int) Math.floor(bulkItemQl / options.getQualityRange()) + 1;
        return depositItemQl < intPart * options.getQualityRange();
    }

    static void makeItemsBulkReflection() {
        ConfigureOptions options = ConfigureOptions.getInstance();
        final int[] successCount = {0};
        ArrayList<Integer> makeItemsBulk = options.getMakeTheseItemsBulk();
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