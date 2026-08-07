package com.rigorberto.zstdnetworkproject;

public final class ZstdSettings {

    private int compressionLevel = 3;
    private boolean fast;
    private int fastLevel = 1;
    private boolean debugMessage = true;

    public int getCompressionLevel() {
        return compressionLevel;
    }

    public void setCompressionLevel(int compressionLevel) {
        this.compressionLevel = compressionLevel;
    }

    public boolean isFast() {
        return fast;
    }

    public void setFast(boolean fast) {
        this.fast = fast;
    }

    public int getFastLevel() {
        return fastLevel;
    }

    public void setFastLevel(int fastLevel) {
        this.fastLevel = fastLevel;
    }

    public int effectiveCompressionLevel() {
        return fast ? -fastLevel : compressionLevel;
    }

    public boolean isDebugMessage() {
        return debugMessage;
    }

    public void setDebugMessage(boolean debugMessage) {
        this.debugMessage = debugMessage;
    }
}
