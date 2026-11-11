package org.jestron.duelsira.duel;

import org.bukkit.configuration.file.FileConfiguration;

/** Kendi envanteriyle atilan normal duellolarin kurallari. */
public final class DuelSettings {

    private boolean allowLadder;
    private boolean allowSlab;
    private boolean allowButton;
    private boolean allowCobweb;
    private boolean allowElytra;

    public DuelSettings(FileConfiguration cfg) {
        this.allowLadder = cfg.getBoolean("defaults.allow-ladder", true);
        this.allowSlab = cfg.getBoolean("defaults.allow-slab", true);
        this.allowButton = cfg.getBoolean("defaults.allow-button", true);
        this.allowCobweb = cfg.getBoolean("defaults.allow-cobweb", true);
        this.allowElytra = cfg.getBoolean("defaults.allow-elytra", true);
    }

    public boolean isAllowLadder() { return allowLadder; }
    public boolean isAllowSlab() { return allowSlab; }
    public boolean isAllowButton() { return allowButton; }
    public boolean isAllowCobweb() { return allowCobweb; }
    public boolean isAllowElytra() { return allowElytra; }

    public void toggleLadder() { allowLadder = !allowLadder; }
    public void toggleSlab() { allowSlab = !allowSlab; }
    public void toggleButton() { allowButton = !allowButton; }
    public void toggleCobweb() { allowCobweb = !allowCobweb; }
    public void toggleElytra() { allowElytra = !allowElytra; }

    public DuelSettings copy(FileConfiguration cfg) {
        DuelSettings s = new DuelSettings(cfg);
        s.allowLadder = allowLadder;
        s.allowSlab = allowSlab;
        s.allowButton = allowButton;
        s.allowCobweb = allowCobweb;
        s.allowElytra = allowElytra;
        return s;
    }

    public String toJson() {
        return "{\"allowLadder\":" + allowLadder + ",\"allowSlab\":" + allowSlab + ",\"allowButton\":" + allowButton
                + ",\"allowCobweb\":" + allowCobweb + ",\"allowElytra\":" + allowElytra + "}";
    }
}
