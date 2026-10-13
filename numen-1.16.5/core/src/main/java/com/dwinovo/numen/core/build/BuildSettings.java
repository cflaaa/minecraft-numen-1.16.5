package com.dwinovo.numen.core.build;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.world.level.block.Block;

public final class BuildSettings {
    private static final BuildSettings INSTANCE = new BuildSettings();
    public boolean buildIgnoreExisting = false;
    public boolean buildIgnoreDirection = false;
    public boolean okIfWater = false;
    private List<Block> buildIgnoreBlocks;
    private List<Block> okIfAir;
    private List<String> buildIgnoreProperties;
    private Map<Block, List<Block>> buildValidSubstitutes;

    public static BuildSettings get() {
        return INSTANCE;
    }

    private BuildSettings() {
    }

    public List<Block> buildIgnoreBlocks() {
        if (this.buildIgnoreBlocks == null) {
            this.buildIgnoreBlocks = new ArrayList<Block>();
        }
        return this.buildIgnoreBlocks;
    }

    public List<Block> okIfAir() {
        if (this.okIfAir == null) {
            this.okIfAir = new ArrayList<Block>();
        }
        return this.okIfAir;
    }

    public List<String> buildIgnoreProperties() {
        if (this.buildIgnoreProperties == null) {
            this.buildIgnoreProperties = new ArrayList<String>();
        }
        return this.buildIgnoreProperties;
    }

    public Map<Block, List<Block>> buildValidSubstitutes() {
        if (this.buildValidSubstitutes == null) {
            this.buildValidSubstitutes = new HashMap<Block, List<Block>>();
        }
        return this.buildValidSubstitutes;
    }
}

