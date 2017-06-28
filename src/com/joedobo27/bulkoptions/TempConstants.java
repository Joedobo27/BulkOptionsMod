package com.joedobo27.bulkoptions;

import java.util.ArrayList;

class TempConstants {
    int[] successesQR;
    boolean replenish;
    boolean rarityStorage;
    int[] successesRS;
    boolean preparedFoodStorage;
    int[] successesPFS;
    ArrayList<Integer> makeItemsBulk = new ArrayList<>();
    TempConstants instance;

    TempConstants(int[] successesQR, boolean replenish, boolean rarityStorage, int[] successesRS, boolean preparedFoodStorage,
                  int[] successesPFS) {
        this.successesQR = successesQR;
        this.replenish = replenish;
        this.rarityStorage = rarityStorage;
        this.successesRS = successesRS;
        this.preparedFoodStorage = preparedFoodStorage;
        this.successesPFS = successesPFS;
        instance = this;
    }

    void setReplenish(boolean replenish) {
        this.replenish = replenish;
    }

    void setRarityStorage(boolean rarityStorage) {
        this.rarityStorage = rarityStorage;
    }

    void setPreparedFoodStorage(boolean preparedFoodStorage) {
        this.preparedFoodStorage = preparedFoodStorage;
    }

    void setMakeItemsBulk(ArrayList<Integer> makeItemsBulk) {
        this.makeItemsBulk = makeItemsBulk;
    }

    ArrayList<Integer> getMakeItemsBulk() {
        return makeItemsBulk;
    }
}
