package com.joedobo27.bulkoptions;

import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Properties;
import java.util.stream.Collectors;

class ConfigureOptions {

     private final int qualityRange;
     private static final int QUALITY_RANGE_DEFAULT = 10;
     private final boolean enableReplenish;
     private static final boolean REPLENISH_DEFAULT = false;
     private final boolean enableRarityStorage;
     private static final boolean RARITY_STORAGE_DEFAULT = false;
     private final boolean enablePreparedFoodStorage;
     private static final boolean PREPARED_FOOD_DEFAULT = false;
     private final ArrayList<Integer> makeTheseItemsBulk;
    private final ConfigureActionOptions replenishActionOptions;

    private static ConfigureOptions instance = null;
    private static final String DEFAULT_ACTION_OPTION = "" +
            "{\"minSkill\":10 ,\"maxSkill\":95 , \"longestTime\":100 , \"shortestTime\":10 , \"minimumStamina\":6000}";

    private ConfigureOptions(int qualityRange, boolean enableReplenish, boolean enableRarityStorage,
                            boolean enablePreparedFoodStorage, ArrayList<Integer> makeTheseItemsBulk,
                             ConfigureActionOptions replenishActionOptions) {
        this.qualityRange = qualityRange;
        this.enableReplenish = enableReplenish;
        this.enableRarityStorage = enableRarityStorage;
        this.enablePreparedFoodStorage = enablePreparedFoodStorage;
        this.makeTheseItemsBulk = makeTheseItemsBulk;
        this.replenishActionOptions = replenishActionOptions;
    }

    synchronized static void setOptions(@Nullable Properties properties) {
        if (instance == null) {
            if (properties == null)
                properties = getProperties();
            if (properties == null)
                throw new RuntimeException("properties can't be null here.");
            instance = new ConfigureOptions(
                    Integer.parseInt(properties.getProperty("qualityRange", Integer.toString(QUALITY_RANGE_DEFAULT))),
                    Boolean.parseBoolean(properties.getProperty("enableReplenish", Boolean.toString(REPLENISH_DEFAULT))),
                    Boolean.parseBoolean(properties.getProperty("enableRarityStorage", Boolean.toString(RARITY_STORAGE_DEFAULT))),
                    Boolean.parseBoolean(properties.getProperty("enablePreparedFoodStorage", Boolean.toString(PREPARED_FOOD_DEFAULT))),
                    getBulkItemList(properties.getProperty("makeTheseItemsBulk")),
                    doPropertiesToConfigureAction(properties.getProperty("replenishAction", DEFAULT_ACTION_OPTION))
            );
        }
    }

    synchronized static void resetOptions() {
        instance = null;
        Properties properties = getProperties();
        if (properties == null)
            throw new RuntimeException("properties can't be null here.");
        instance = new ConfigureOptions(
                Integer.parseInt(properties.getProperty("qualityRange", Integer.toString(QUALITY_RANGE_DEFAULT))),
                Boolean.parseBoolean(properties.getProperty("enableReplenish", Boolean.toString(REPLENISH_DEFAULT))),
                Boolean.parseBoolean(properties.getProperty("enableRarityStorage", Boolean.toString(RARITY_STORAGE_DEFAULT))),
                Boolean.parseBoolean(properties.getProperty("enablePreparedFoodStorage", Boolean.toString(PREPARED_FOOD_DEFAULT))),
                getBulkItemList(properties.getProperty("makeTheseItemsBulk")),
                doPropertiesToConfigureAction(properties.getProperty("replenishAction", DEFAULT_ACTION_OPTION))
        );
        BulkOptionsMod.makeItemsBulkReflection();
    }

    private static Properties getProperties() {
        try {
            File configureFile = new File("mods/BulkOptionsMod.properties");
            FileInputStream configureStream = new FileInputStream(configureFile);
            Properties configureProperties = new Properties();
            configureProperties.load(configureStream);
            return configureProperties;
        }catch (IOException e) {
            BulkOptionsMod.logger.warning(e.getMessage());
            return null;
        }
    }

    private static ArrayList<Integer> getBulkItemList(String values) {
        if (values == null || values.length() == 0)
            return null;
        return Arrays.stream(values.replaceAll("\\s", "").split(","))
                .mapToInt(Integer::parseInt)
                .boxed()
                .collect(Collectors.toCollection(ArrayList::new));
    }

    private static ConfigureActionOptions doPropertiesToConfigureAction(String values) {

        ArrayList<Integer> integers = Arrays.stream(values.replaceAll("\\s", "").split(","))
                .mapToInt(Integer::parseInt)
                .boxed()
                .collect(Collectors.toCollection(ArrayList::new));

        int minSkill = integers.get(0);
        int maxSkill = integers.get(1);
        int longestTime = integers.get(2);
        int shortestTime = integers.get(3);
        int minimumStamina = integers.get(4);
        return new ConfigureActionOptions(minSkill, maxSkill, longestTime, shortestTime, minimumStamina);
    }

    static ConfigureOptions getInstance() {
        return instance;
    }

    int getQualityRange() {
        return qualityRange;
    }

    boolean isEnableReplenish() {
        return enableReplenish;
    }

    boolean isEnableRarityStorage() {
        return enableRarityStorage;
    }

    boolean isEnablePreparedFoodStorage() {
        return enablePreparedFoodStorage;
    }

    ArrayList<Integer> getMakeTheseItemsBulk() {
        return makeTheseItemsBulk;
    }

    ConfigureActionOptions getReplenishActionOptions() {
        return replenishActionOptions;
    }
}
