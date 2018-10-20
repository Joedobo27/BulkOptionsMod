package com.joedobo27.bom;


import com.wurmonline.server.creatures.Communicator;
import com.wurmonline.server.items.ItemTemplate;
import com.wurmonline.server.items.ItemTemplateFactory;
import javassist.*;
import javassist.bytecode.Descriptor;
import javassist.bytecode.Opcode;
import javassist.expr.ExprEditor;
import javassist.expr.MethodCall;
import org.gotti.wurmunlimited.modloader.ReflectionUtil;
import org.gotti.wurmunlimited.modloader.classhooks.HookManager;
import org.gotti.wurmunlimited.modloader.interfaces.*;
import org.gotti.wurmunlimited.modsupport.actions.ModActions;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Objects;
import java.util.Properties;
import java.util.logging.Logger;


public class BulkOptionsMod implements WurmServerMod, PreInitable, Configurable, ServerStartedListener, PlayerMessageListener {

    static final Logger logger = Logger.getLogger(BulkOptionsMod.class.getName());
    static private ClassPool classPool;

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
        classPool = HookManager.getInstance().getClassPool();

        ConfigureOptions options = ConfigureOptions.getInstance();

        try {
            classPool.get("com.wurmonline.server.items.Item").getClassFile().compact();
        } catch (NotFoundException ignored){}

        if (options.isEnableBulkQualitySorting()) {
            injectItemSortingMethods();
        }
        if (options.isEnableBulkQualitySorting() || options.isEnableRarityStorage()){
            addBulkItemBytecode();
            addBulkItemToCrateBytecode();
        }
        if (options.isEnableRarityStorage()) {
            answerBytecode();
        }
        if (options.isEnablePreparedFoodStorage() || options.isEnableRarityStorage()) {
            moveToItemBytecode();
        }
    }

    @Override
    public void onServerStarted() {

        ReplenishActionPerformer replenishActionPerformer = ReplenishActionPerformer.getReplenishActionPerformer();
        ModActions.registerAction(replenishActionPerformer.getActionEntry());
        ModActions.registerAction(replenishActionPerformer);
        if (ConfigureOptions.getInstance().isEnableBulkQualitySorting()) {
            ConfigureBinActionPerformer configureBinActionPerformer = ConfigureBinActionPerformer.getConfigureBinActionPerformer();
            ModActions.registerAction(configureBinActionPerformer.getActionEntry());
            ModActions.registerAction(configureBinActionPerformer);
        }

        if (ConfigureOptions.getInstance().getMakeTheseItemsBulk() != null)
            makeItemsBulkReflection();
    }

    private void injectItemSortingMethods(){
        try {
            CtClass ctClassItem = classPool.get("com.wurmonline.server.items.Item");
            classPool.importPackage("com.wurmonline.server.items.Item");
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
                    "int qualityRange = Math.min(101, bulkContainer.getData2() == -1 ? 101 : bulkContainer.getData2() == 0 ? 101 : bulkContainer.getData2());"+
                    "int intPartLower = (int) Math.floor((double)(bulkItems[i].getQualityLevel() / qualityRange));"+
                    "boolean isLowerBoundGTEQ = depositItem.getQualityLevel() >= intPartLower * qualityRange;"+
                    "int intPartUpper = (int) Math.floor((double)(bulkItems[i].getQualityLevel() / qualityRange)) + 1;"+
                    "boolean isUpperBoundLT = depositItem.getQualityLevel() < intPartUpper * qualityRange;"+
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
     *      insert after ItemFactory.createItem(){...}
     */
    private static void addBulkItemBytecode() {
        try {
            CtClass itemCtCl = classPool.get("com.wurmonline.server.items.Item");
            CtMethod ctMethodAddBulkItem = itemCtCl.getMethod("AddBulkItem",
                    Descriptor.ofMethod(CtPrimitiveType.booleanType, new CtClass[]{
                            classPool.get("com.wurmonline.server.creatures.Creature"),
                            classPool.get("com.wurmonline.server.items.Item")}));

            if (ConfigureOptions.getInstance().isEnableRarityStorage()) {
                // Find this
                // toaddTo = ItemFactory.createItem(669, this.getCurrentQualityLevel(), this.getMaterial(), (byte)0, null);
                ByteCodeWild search = new ByteCodeWild(itemCtCl.getClassFile().getConstPool(),
                        ctMethodAddBulkItem.getMethodInfo().getCodeAttribute());
                search.addIconst(669);
                search.addAload("this", "Lcom/wurmonline/server/items/Item;");
                search.addInvokevirtual("com.wurmonline.server.items.Item","getCurrentQualityLevel",
                        "()F");
                search.addAload("this", "Lcom/wurmonline/server/items/Item;");
                search.addInvokevirtual("com.wurmonline.server.items.Item", "getMaterial","()B");
                search.addIconst(0);
                search.addConstZero(classPool.get("java.lang.String"));
                search.addInvokestatic("com.wurmonline.server.items.ItemFactory",  "createItem",
                        "(IFBBLjava/lang/String;)Lcom/wurmonline/server/items/Item;");
                search.addAstore("toaddTo", "Lcom/wurmonline/server/items/Item;");

                search.trimFoundBytecode();

                ctMethodAddBulkItem.insertAt(search.getTableLineNumberAfter(), "toaddTo.setRarity(this.getRarity());");
            }

            if (ConfigureOptions.getInstance().isEnableBulkQualitySorting()) {
                final boolean[] qualitySortOkay = {false};
                ctMethodAddBulkItem.instrument(new ExprEditor() {
                    @Override
                    public void edit(MethodCall methodCall) throws CannotCompileException {
                        if (Objects.equals("getItemWithTemplateAndMaterial", methodCall.getMethodName())) {
                            logger.fine("replace on getItemWithTemplateAndMaterial inside Item.AddBulkItem() at line "
                                    + methodCall.getLineNumber());
                            methodCall.replace(
                                    "$_ = com.wurmonline.server.items.Item.getTargetToAdd(" +
                                            "target, this, this.getMaterial(), auxToCheck, this.getRealTemplateId());");
                            qualitySortOkay[0] = true;
                        }
                    }
                });
                if (!qualitySortOkay[0])
                    logger.warning("Error enabling bulk-ql-sort in addBulkItemBytecode");
            }
        } catch (NotFoundException | CannotCompileException | RuntimeException e) {
            logger.warning(e.getMessage());
        }
    }

    /**
     * Change Item.AddBulkItemToCrate
     *      1)so instead of calling getItemWithTemplateAndMaterial() to find matching items
     *          in a bulk container it will call this mod's hook method, getTargetToAdd().
     *      2) Insert a statement that will make new rarity bulk entries.
     *          insert after ItemFactory.createItem(){...}
     */
    private static void addBulkItemToCrateBytecode(){

        ConfigureOptions options = ConfigureOptions.getInstance();
        try {
            CtClass itemCtCl = classPool.get("com.wurmonline.server.items.Item");
            CtMethod addBulkItemToCrate = itemCtCl.getMethod("AddBulkItemToCrate",
                    Descriptor.ofMethod(CtPrimitiveType.booleanType, new CtClass[]{
                            classPool.get("com.wurmonline.server.creatures.Creature"),
                            classPool.get("com.wurmonline.server.items.Item")}));

            if (options.isEnableBulkQualitySorting()) {
                final boolean[] qualitySortOkay = {false};
                addBulkItemToCrate.instrument(new ExprEditor() {
                    @Override
                    public void edit(MethodCall methodCall) throws CannotCompileException {
                        if (Objects.equals("getItemWithTemplateAndMaterial", methodCall.getMethodName())) {
                            logger.fine("replace on getItemWithTemplateAndMaterial inside Item.AddBulkItemToCrate() at line "
                                    + methodCall.getLineNumber());
                            methodCall.replace("$_ = com.wurmonline.server.items.Item.getTargetToAdd(" +
                                    "target, this, this.getMaterial(), auxToCheck, this.getRealTemplateId());");
                            qualitySortOkay[0] = true;
                        }
                    }
                });
                if (!qualitySortOkay[0])
                    logger.warning("Error enabling bulk-ql-sort in addBulkItemBytecode");
            }
            if (options.isEnableRarityStorage()){
                // Find this
                // toaddTo = ItemFactory.createItem(669, this.getCurrentQualityLevel(), this.getMaterial(), (byte)0, null);
                ByteCodeWild search = new ByteCodeWild(itemCtCl.getClassFile().getConstPool(),
                        addBulkItemToCrate.getMethodInfo().getCodeAttribute());
                search.addIconst(669);
                search.addAload("this", "Lcom/wurmonline/server/items/Item;");
                search.addInvokevirtual("com.wurmonline.server.items.Item","getCurrentQualityLevel",
                        "()F");
                search.addAload("this", "Lcom/wurmonline/server/items/Item;");
                search.addInvokevirtual("com.wurmonline.server.items.Item", "getMaterial","()B");
                search.addIconst(0);
                search.addConstZero(classPool.get("java.lang.String"));
                search.addInvokestatic("com.wurmonline.server.items.ItemFactory",  "createItem",
                        "(IFBBLjava/lang/String;)Lcom/wurmonline/server/items/Item;");
                search.addAstore("toaddTo", "Lcom/wurmonline/server/items/Item;");

                search.trimFoundBytecode();

                addBulkItemToCrate.insertAt(search.getTableLineNumberAfter(), "toaddTo.setRarity(this.getRarity());");
            }

        }catch (NotFoundException | CannotCompileException | RuntimeException e){
            logger.warning(e.getMessage());
        }
    }

    /**
     * insert into RemoveItemQuestion.answer() code to handle making the withdrawn item of rarity.
     * insert after-
         *      ItemFactory.createItem(){...}
     */
    private static void answerBytecode(){
        try {
            CtClass ctClass = classPool
                    .get("com.wurmonline.server.questions.RemoveItemQuestion");
            ctClass.getClassFile().compact();
            CtMethod ctMethod =  ctClass.getMethod("answer",
                    Descriptor.ofMethod(CtPrimitiveType.voidType, new CtClass[]{
                            classPool.get("java.util.Properties")
                    }));

            ByteCodeWild find = new ByteCodeWild(ctClass.getClassFile().getConstPool(),
                    ctMethod.getMethodInfo().getCodeAttribute());
            find.addIload("toMake", "I");
            find.addAload("bulkitem", "Lcom/wurmonline/server/items/Item;");
            find.addInvokevirtual("com.wurmonline.server.items.Item", "getCurrentQualityLevel",
                    "()F");
            find.addAload("bulkitem", "Lcom/wurmonline/server/items/Item;");
            find.addInvokevirtual("com.wurmonline.server.items.Item", "getMaterial", "()B");
            find.addIconst(0);
            find.addAload("this", "Lcom/wurmonline/server/questions/RemoveItemQuestion;");
            find.addInvokevirtual("com.wurmonline.server.questions.RemoveItemQuestion", "getResponder",
                    "()Lcom/wurmonline/server/creatures/Creature;");
            find.addInvokevirtual("com.wurmonline.server.creatures.Creature", "getName",
                    "()Ljava/lang/String;");
            find.addInvokestatic("com.wurmonline.server.items.ItemFactory", "createItem",
                    "(IFBBLjava/lang/String;)Lcom/wurmonline/server/items/Item;");
            find.addAstore("toInsert", "Lcom/wurmonline/server/items/Item;");

            find.trimFoundBytecode();

            ctMethod.insertAt(find.getTableLineNumberAfter(), "toInsert.setRarity(bulkitem.getRarity());");
        }catch (NotFoundException | CannotCompileException | RuntimeException e){
            logger.warning(e.getMessage());
        }
    }

    /**
     * Change Item.moveToItem() inside the if (target.isBulkContainer()) {...} code block so
     *      1)so food items can be placed in bulk. Methods isDish() and usesFoodState() will always be false disabling
     *      the limiting logic statements. line 3402: 5419
     * 2) so rare items can go in bulk; logic, if (this.getRarity() > 0) {...} is always false. line 3345: 4988
     */
    private void moveToItemBytecode(){
        ConfigureOptions options = ConfigureOptions.getInstance();
        Boolean[] successes;
        if (!options.isEnablePreparedFoodStorage())
            successes = new Boolean[]{true, true, false};
        else if (!options.isEnableRarityStorage())
            successes = new Boolean[]{false, false, true};
        else
            successes = new Boolean[]{false, false, false};
        try {
            CtClass ctClassItem = classPool.get("com.wurmonline.server.items.Item");

            ctClassItem.debugWriteFile("C:\\Users\\Jason\\Documents\\WU\\WU-Server\\byte code prints\\");


            CtClass returnType = CtPrimitiveType.booleanType;
            CtClass[] paramTypes = {
                    classPool.get("com.wurmonline.server.creatures.Creature"),
                    CtPrimitiveType.longType, CtPrimitiveType.booleanType
            };
            CtMethod ctMethodMoveToItem = ctClassItem.getMethod("moveToItem", Descriptor.ofMethod(returnType, paramTypes));

            ByteCodeWild find = new ByteCodeWild(ctClassItem.getClassFile().getConstPool(),
                    ctMethodMoveToItem.getMethodInfo().getCodeAttribute());
            find.addAload("this", "Lcom/wurmonline/server/items/Item;");
            find.addInvokevirtual("com.wurmonline.server.items.Item", "getRarity", "()B");
            find.addCodeBranchingWild(Opcode.IFLE);
            find.trimFoundBytecode();
            int rarityLine = find.getTableLineNumberStart();

            ByteCodeWild find1 = new ByteCodeWild(ctClassItem.getClassFile().getConstPool(),
                    ctMethodMoveToItem.getMethodInfo().getCodeAttribute());
            find1.addAload("this", "Lcom/wurmonline/server/items/Item;");
            find1.addInvokevirtual("com.wurmonline.server.items.Item", "isDish", "()Z");
            find1.addCodeBranchingWild(Opcode.IFNE);
            find1.addAload("this", "Lcom/wurmonline/server/items/Item;");
            find1.addInvokevirtual("com.wurmonline.server.items.Item", "usesFoodState", "()Z");
            find1.addCodeBranchingWild(Opcode.IFEQ);
            find1.trimFoundBytecode();
            int foodStateLine = find1.getTableLineNumberStart();

            ctMethodMoveToItem.instrument(new ExprEditor() {
                @Override
                public void edit(MethodCall methodCall) throws CannotCompileException {
                    if (Objects.equals("isDish", methodCall.getMethodName()) && options.isEnablePreparedFoodStorage()){
                        logger.fine("r" +
                                "eplace on isDish inside Item.moveToItem() at line " + methodCall.getLineNumber());
                        methodCall.replace("$_ = false;");
                        successes[0] = true;
                    } else if (Objects.equals("usesFoodState", methodCall.getMethodName()) &&
                            options.isEnablePreparedFoodStorage() && methodCall.getLineNumber() == foodStateLine) {
                        logger.fine("replace on usesFoodState inside Item.moveToItem() at line " + methodCall.getLineNumber());
                        methodCall.replace("$_ = false;");
                        successes[1] = true;
                    }
                    else if (Objects.equals("getRarity", methodCall.getMethodName()) &&
                            options.isEnableRarityStorage() && methodCall.getLineNumber() == rarityLine) {
                            logger.fine("replace on getRarity inside Item.moveToItem() at line " + methodCall.getLineNumber());
                            methodCall.replace("$_ = 0;");
                            successes[2] = true;
                        }
                    }
            });
        }catch (NotFoundException | CannotCompileException  | RuntimeException e){
            logger.warning(e.getMessage());
        }
        if (Arrays.stream(successes).anyMatch(aBoolean -> !aBoolean))
            logger.warning(String.format("problem in moveToItemBytecode, %s", Arrays.toString(successes)));
    }

    static private void makeItemsBulkReflection() {
        ArrayList<Integer> makeItemsBulk = ConfigureOptions.getInstance().getMakeTheseItemsBulk();

        boolean fail = Arrays.stream(ItemTemplateFactory.getInstance().getTemplates())
                .filter(itemTemplate -> makeItemsBulk.stream()
                        .anyMatch(value -> Objects.equals(value, itemTemplate.getTemplateId())))
                .map(BulkOptionsMod::setFieldBulk)
                .anyMatch(aBoolean -> !aBoolean);
        if (fail)
            logger.info("Make items bulk FAIL");
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
}