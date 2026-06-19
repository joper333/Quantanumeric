package com.beatrix.quanum;

import com.beatrix.quanum.effect.SwapBlockEffect;
import com.hypixel.hytale.builtin.triggervolumes.effect.TriggerEffect;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.server.core.plugin.JavaPlugin;
import com.hypixel.hytale.server.core.plugin.JavaPluginInit;

import java.util.logging.Level;

public class Main extends JavaPlugin {

    public static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();

    public Main(JavaPluginInit init) {
        super(init);
    }

    @Override
    protected void start() {
        LOGGER.at(Level.INFO).log("Starting Quantanumeric trigger volumes!");
    }

    @Override
    protected void setup() {
        LOGGER.at(Level.INFO).log("Setting up Quantanumeric trigger volumes!");

        TriggerEffect.CODEC.register("SwapBlock", SwapBlockEffect.class, SwapBlockEffect.CODEC);
    }

    @Override
    protected void shutdown() {
        LOGGER.at(Level.INFO).log("Shutting down Quantanumeric trigger volumes!");
    }
}
