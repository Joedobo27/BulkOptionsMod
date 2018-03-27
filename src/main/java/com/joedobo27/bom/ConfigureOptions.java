package com.joedobo27.bom;


import javax.json.Json;
import javax.json.JsonObject;
import javax.json.JsonReader;
import java.io.*;
import java.util.ArrayList;
import java.util.Properties;
import java.util.stream.IntStream;

class ConfigureOptions {

    private boolean enableReplenish;
    private boolean enableRarityStorage;
    private boolean enablePreparedFoodStorage;
    private boolean enableBulkQualitySorting;
    private ArrayList<Integer> makeTheseItemsBulk;
    private ActionOptions replenishAction;

    private static final ConfigureOptions instance;
    private static final String DEFAULT_ACTION_OPTION = "" +
            "{\"minSkill\":10 ,\"maxSkill\":95 , \"longestTime\":100 , \"shortestTime\":10 , \"minimumStamina\":6000}";

    static {
        instance = new ConfigureOptions();
    }

    class ActionOptions {
        private final int minSkill;
        private final int maxSkill;
        private final int longestTime;
        private final int shortestTime;
        private final int minimumStamina;

        ActionOptions(int minSkill, int maxSkill, int longestTime, int shortestTime, int minimumStamina) {
            this.minSkill = minSkill;
            this.maxSkill = maxSkill;
            this.longestTime = longestTime;
            this.shortestTime = shortestTime;
            this.minimumStamina = minimumStamina;
        }

        int getMinSkill() {
            return minSkill;
        }

        int getMaxSkill() {
            return maxSkill;
        }

        int getLongestTime() {
            return longestTime;
        }

        int getShortestTime() {
            return shortestTime;
        }

        int getMinimumStamina() {
            return minimumStamina;
        }
    }

    synchronized static void setOptions(Properties properties) {
        instance.enableReplenish = Boolean.parseBoolean(properties.getProperty("enableReplenish"));
        instance.enableRarityStorage = Boolean.parseBoolean(properties.getProperty("enableRarityStorage"));
        instance.enablePreparedFoodStorage = Boolean.parseBoolean(properties.getProperty("enablePreparedFoodStorage"));
        instance.enableBulkQualitySorting = Boolean.parseBoolean(properties.getProperty("enableBulkQualitySorting"));
        instance.makeTheseItemsBulk = doPropertiesToArray(properties.getProperty("makeTheseItemsBulk"));
        instance.replenishAction = doPropertiesToActionOptions(properties.getProperty("replenishAction"));
    }

    synchronized static void resetOptions() {
        Properties properties = getProperties();
        if (properties == null)
            throw new RuntimeException("properties can't be null here.");
        instance.enableReplenish = Boolean.parseBoolean(properties.getProperty("enableReplenish"));
        instance.enableRarityStorage = Boolean.parseBoolean(properties.getProperty("enableRarityStorage"));
        instance.enablePreparedFoodStorage = Boolean.parseBoolean(properties.getProperty("enablePreparedFoodStorage"));
        instance.enableBulkQualitySorting = Boolean.parseBoolean(properties.getProperty("enableBulkQualitySorting"));
        instance.makeTheseItemsBulk = doPropertiesToArray(properties.getProperty("makeTheseItemsBulk"));
        instance.replenishAction = doPropertiesToActionOptions(properties.getProperty("replenishAction"));
    }

    private static ArrayList<Integer> doPropertiesToArray(String values) {
        String[] strings = values.split(",");
        ArrayList<Integer> integers = new ArrayList<>();
        IntStream.range(0, strings.length)
                .forEach(value -> integers.add(Integer.parseInt(strings[value])));
        return integers;
    }

    private static ActionOptions doPropertiesToActionOptions(String values) {
        Reader reader = new StringReader(values);
        JsonReader jsonReader = Json.createReader(reader);
        JsonObject jsonObject = jsonReader.readObject();
        int minSkill = jsonObject.getInt("minSkill", 10);
        int maxSkill = jsonObject.getInt("maxSkill", 95);
        int longestTime = jsonObject.getInt("longestTime", 100);
        int shortestTime = jsonObject.getInt("shortestTime", 10);
        int minimumStamina = jsonObject.getInt("minimumStamina", 6000);
        return instance.new ActionOptions(minSkill, maxSkill, longestTime, shortestTime, minimumStamina);
    }

    private static Properties getProperties() {
        try {
            File configureFile = new File("mods/FarmBarrelMod.properties");
            FileInputStream configureStream = new FileInputStream(configureFile);
            Properties configureProperties = new Properties();
            configureProperties.load(configureStream);
            return configureProperties;
        }catch (IOException e) {
            BulkOptionsMod.logger.warning(e.getMessage());
            return null;
        }
    }

    static ConfigureOptions getInstance() {
        return instance;
    }

    public boolean isEnableBulkQualitySorting() {
        return enableBulkQualitySorting;
    }

    public boolean isEnableReplenish() {
        return enableReplenish;
    }

    public boolean isEnableRarityStorage() {
        return enableRarityStorage;
    }

    public boolean isEnablePreparedFoodStorage() {
        return enablePreparedFoodStorage;
    }

    public ArrayList<Integer> getMakeTheseItemsBulk() {
        return makeTheseItemsBulk;
    }

    public ActionOptions getReplenishAction() {
        return replenishAction;
    }
}
