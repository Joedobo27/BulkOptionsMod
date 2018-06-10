package com.joedobo27.bom;


import com.wurmonline.server.creatures.Communicator;
import com.wurmonline.server.items.Item;
import com.wurmonline.server.items.ItemTemplate;
import com.wurmonline.server.items.ItemTemplateFactory;
import javassist.*;
import javassist.bytecode.*;
import javassist.expr.ExprEditor;
import javassist.expr.MethodCall;
import org.gotti.wurmunlimited.modloader.ReflectionUtil;
import org.gotti.wurmunlimited.modloader.classhooks.HookManager;
import org.gotti.wurmunlimited.modloader.interfaces.*;
import org.gotti.wurmunlimited.modsupport.actions.ModActions;

import java.lang.reflect.Field;
import java.nio.ByteBuffer;
import java.util.*;
import java.util.logging.Logger;
import java.util.stream.IntStream;


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

        ConfigureOptions options = ConfigureOptions.getInstance();

        try {
            CtClass ctClassItem = HookManager.getInstance().getClassPool().get("com.wurmonline.server.items.Item");
            ctClassItem.getClassFile().compact();
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
            CtMethod ctMethodAddBulkItem = HookManager.getInstance().getClassPool().get("com.wurmonline.server.items.Item").getMethod("AddBulkItem",
                    Descriptor.ofMethod(CtPrimitiveType.booleanType, new CtClass[]{
                            HookManager.getInstance().getClassPool().get("com.wurmonline.server.creatures.Creature"),
                            HookManager.getInstance().getClassPool().get("com.wurmonline.server.items.Item")}));

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
            if (ConfigureOptions.getInstance().isEnableRarityStorage()) {
                // Get the insert point line number which is just after the call to ItemFactory.createItem().
                Bytecode find = new Bytecode(HookManager.getInstance().getClassPool()
                        .get("com.wurmonline.server.items.Item").getClassFile().getConstPool());
                find.addAload(5);
                find.addAload(0);
                find.addInvokevirtual("com.wurmonline.server.items.Item", "getTemplateId", "()I");
                find.addInvokevirtual("com.wurmonline.server.items.Item", "setRealTemplate", "(I)V");
                int lineNumber = byteArrayToLineNumber(find.get(), ctMethodAddBulkItem, 9);

                ctMethodAddBulkItem.insertAt(lineNumber, "toaddTo.setRarity(this.getRarity());");
            }
        } catch (NotFoundException | CannotCompileException | RuntimeException | BadBytecode e) {
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
            CtMethod ctMethodAddBulkItem = HookManager.getInstance().getClassPool().get("com.wurmonline.server.items.Item").getMethod("AddBulkItemToCrate",
                    Descriptor.ofMethod(CtPrimitiveType.booleanType, new CtClass[]{
                            HookManager.getInstance().getClassPool().get("com.wurmonline.server.creatures.Creature"),
                            HookManager.getInstance().getClassPool().get("com.wurmonline.server.items.Item")}));

            if (options.isEnableBulkQualitySorting()) {
                final boolean[] qualitySortOkay = {false};
                ctMethodAddBulkItem.instrument(new ExprEditor() {
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
                // Get the insert point line number which is just after the call to ItemFactory.createItem().
                Bytecode find = new Bytecode(HookManager.getInstance().getClassPool()
                        .get("com.wurmonline.server.items.Item").getClassFile().getConstPool());
                find.addAload(5);
                find.addAload(0);
                find.addInvokevirtual("com.wurmonline.server.items.Item", "getTemplateId", "()I");
                find.addInvokevirtual("com.wurmonline.server.items.Item", "setRealTemplate", "(I)V");
                int lineNumber = byteArrayToLineNumber(find.get(), ctMethodAddBulkItem, 9);

                ctMethodAddBulkItem.insertAt(lineNumber, "toaddTo.setRarity(this.getRarity());");
            }

        }catch (NotFoundException | CannotCompileException | RuntimeException | BadBytecode e){
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
            CtClass ctClass = HookManager.getInstance().getClassPool()
                    .get("com.wurmonline.server.questions.RemoveItemQuestion");
            ctClass.getClassFile().compact();
            CtMethod ctMethod =  ctClass.getMethod("answer",
                    Descriptor.ofMethod(CtPrimitiveType.voidType, new CtClass[]{
                            HookManager.getInstance().getClassPool().get("java.util.Properties")
                    }));

            Bytecode find = new Bytecode(ctClass.getClassFile().getConstPool());
            find.addAload(10);
            find.addAload(0);
            find.addInvokevirtual("com.wurmonline.server.questions.RemoveItemQuestion", "getResponder",
                    "()Lcom/wurmonline/server/creatures/Creature;");
            find.addInvokevirtual("com.wurmonline.server.creatures.Creature", "getWurmId", "()J");
            find.addInvokevirtual("com.wurmonline.server.items.Item", "setLastOwnerId", "(J)V");

            int lineNumber = byteArrayToLineNumber(find.get(), ctMethod, 12);

            ctMethod.insertAt(lineNumber, "toInsert.setRarity(bulkitem.getRarity());");
        }catch (NotFoundException | CannotCompileException | BadBytecode e){
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
            CtClass ctClassItem = HookManager.getInstance().getClassPool().get("com.wurmonline.server.items.Item");

            CtClass returnType = CtPrimitiveType.booleanType;
            CtClass[] paramTypes = {
                    HookManager.getInstance().getClassPool().get("com.wurmonline.server.creatures.Creature"),
                    CtPrimitiveType.longType, CtPrimitiveType.booleanType
            };
            CtMethod ctMethodMoveToItem = ctClassItem.getMethod("moveToItem", Descriptor.ofMethod(returnType, paramTypes));

            /////////// TESTING /////////////////
            ctClassItem.debugWriteFile("C:\\Users\\Jason\\Documents\\WU\\WU-Server\\byte code prints");
            /////////// TESTING /////////////////

            Bytecode find = new Bytecode(ctClassItem.getClassFile().getConstPool());
            find.addAload(0);
            find.addInvokevirtual("com.wurmonline.server.items.Item", "getRarity", "()B");
            codeBranching(find, Opcode.IFLE, 45);
            int rarityLine = byteArrayToLineNumber(find.get(), ctMethodMoveToItem, 7);

            Bytecode find1 = new Bytecode(ctClassItem.getClassFile().getConstPool());
            find1.addAload(0);
            find1.addInvokevirtual("com.wurmonline.server.items.Item", "isDish", "()Z");
            codeBranching(find1, Opcode.IFNE, 30);
            find1.addAload(0);
            find1.addInvokevirtual("com.wurmonline.server.items.Item", "usesFoodState", "()Z");
            codeBranching(find1, Opcode.IFEQ, 111);
            int foodStateLine = byteArrayToLineNumber(find1.get(), ctMethodMoveToItem, 14);

            ctMethodMoveToItem.instrument(new ExprEditor() {
                @Override
                public void edit(MethodCall methodCall) throws CannotCompileException {
                    if (Objects.equals("isDish", methodCall.getMethodName()) && options.isEnablePreparedFoodStorage()){
                        logger.fine("replace on isDish inside Item.moveToItem() at line " + methodCall.getLineNumber());
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
        }catch (NotFoundException | CannotCompileException | BadBytecode e){
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

    private static int byteArrayToLineNumber(byte[] bytesSeek, CtMethod ctMethod, int byteArraySize)
            throws BadBytecode, RuntimeException {

        // Using bytesSeek iterate through the ctMethod's bytecode looking for a matching byte array sized to byteArraySize
        int bytecodeIndex = -1;
        CodeIterator codeIterator = ctMethod.getMethodInfo().getCodeAttribute().iterator();
        codeIterator.begin();
        long find = byteArrayToLong(bytesSeek);
        while (codeIterator.hasNext() && codeIterator.lookAhead() + byteArraySize < codeIterator.getCodeLength()) {
            int index = codeIterator.next();
            byte[] bytesFound = new byte[byteArraySize];
            for(int i=0;i<byteArraySize;i++){
                bytesFound[i] = (byte)codeIterator.byteAt(index + i);
            }
            long found = byteArrayToLong(bytesFound);
            if (found == find) {
                bytecodeIndex = index;
            }
        }
        if (bytecodeIndex == -1)
            throw new RuntimeException("no bytecode match found.");
        // Get the line number table entry for the bytecodeIndex.
        LineNumberAttribute lineNumberAttribute = (LineNumberAttribute) ctMethod.getMethodInfo().getCodeAttribute()
                .getAttribute(LineNumberAttribute.tag);
        int lineNumber = lineNumberAttribute.toLineNumber(bytecodeIndex);
        int lineNumberTableOrdinal =  IntStream.range(0, lineNumberAttribute.tableLength())
                .filter(value -> Objects.equals(lineNumberAttribute.lineNumber(value), lineNumber))
                .findFirst()
                .orElseThrow(RuntimeException::new);
        return lineNumberAttribute.lineNumber(lineNumberTableOrdinal);
    }

    private static long byteArrayToLong(byte[] bytesOriginal) {
        if (bytesOriginal.length < 8) {
            byte[] bytesLongPadded = new byte[8];
            System.arraycopy(bytesOriginal, 0, bytesLongPadded, 8 - bytesOriginal.length,
                    bytesOriginal.length);
            return ByteBuffer.wrap(bytesLongPadded).getLong();
        }
        else
            return ByteBuffer.wrap(bytesOriginal).getLong();
    }

    private static void codeBranching(Bytecode bytecode, int opcode, int branchCount){
        bytecode.addOpcode(opcode);
        bytecode.add((branchCount >>> 8) & 0xFF, branchCount & 0xFF);
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
    @Deprecated
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

    @Deprecated
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
            int qualityRange = Math.max(100, bulkContainer.getData2() == -1 ? 100 : bulkContainer.getData2() == 0 ? 100 : bulkContainer.getData2());
            int intPartLower = (int) Math.floor((double)bulkItems[i].getQualityLevel() / qualityRange);
            boolean isLowerBoundGTEQ = depositItem.getQualityLevel() >= intPartLower * qualityRange;
            int intPartUpper = (int) Math.floor((double)bulkItems[i].getQualityLevel() / qualityRange) + 1;
            boolean isUpperBoundLT = depositItem.getQualityLevel() < intPartUpper * qualityRange;
            if (!isLowerBoundGTEQ || !isUpperBoundLT)
                continue;
            itemMatch = bulkItems[i];
            break;
        }
        return itemMatch;
    }

    @Deprecated
    private static boolean isLowerBoundGTEQ(double bulkItemQl, double depositItemQl){
        int intPart = (int) Math.floor(bulkItemQl / 10);
        return depositItemQl >= intPart * 10;
    }

    @Deprecated
    private static boolean isUpperBoundLT(double bulkItemQl, double depositItemQl){
        int intPart = (int) Math.floor(bulkItemQl / 10) + 1;
        return depositItemQl < intPart * 10;
    }
}