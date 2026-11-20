package dev.chestlock;

import dev.chestlock.command.ChestLockCommand;
import dev.chestlock.listener.ChestLockListener;
import dev.chestlock.listener.HopperListener;
import dev.chestlock.listener.PvpRetaliationListener;
import dev.chestlock.listener.ShareInputListener;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

/**
 * Chest Lock - チェストのロック・共有を行うプラグイン。
 */
public final class ChestLockPlugin extends JavaPlugin {

    private Messages messages;
    private Sounds sounds;
    private ChestStore store;
    private ShareInputListener shareInput;
    private PvpRetaliationListener pvpRetaliation;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        FileConfiguration defaults = loadBundledDefaults();
        // 旧バージョンの config.yml に新しいキーを自動追記する（既存の設定は保持）
        getConfig().setDefaults(defaults);
        getConfig().options().copyDefaults(true);
        saveConfig();
        messages = new Messages();
        messages.load(getConfig(), defaults);
        sounds = new Sounds();
        sounds.load(getConfig(), defaults, getLogger());
        try {
            store = new ChestStore(this);
            store.load();
        } catch (Exception e) {
            // 読み込み失敗（接続失敗・ファイル破損・移行失敗）を「データなし」と区別し、
            // 保護なしで稼働させない
            getLogger().log(java.util.logging.Level.SEVERE, "データの読み込みに失敗したため ChestLock を停止します。", e);
            getServer().getPluginManager().disablePlugin(this);

            // safety mechanism that protect privacy chest
            getServer().shutdown();
            return;
        }

        shareInput = new ShareInputListener(this);
        pvpRetaliation = new PvpRetaliationListener(this,
                getConfig().getInt("penalty.pvp-duration-minutes", 5) * 60_000L);
        getServer().getPluginManager().registerEvents(new ChestLockListener(this), this);
        getServer().getPluginManager().registerEvents(new HopperListener(this), this);
        getServer().getPluginManager().registerEvents(shareInput, this);
        getServer().getPluginManager().registerEvents(pvpRetaliation, this);

        ChestLockCommand command = new ChestLockCommand(this);
        getCommand("chestlock").setExecutor(command);
        getCommand("chestlock").setTabCompleter(command);

        getLogger().info("ChestLock v" + getPluginMeta().getVersion() + " を有効化しました。");
    }

    @Override
    public void onDisable() {
        if (pvpRetaliation != null) {
            pvpRetaliation.restoreWorlds();
        }
        if (store != null) {
            store.close();
        }
    }

    /** jar 内蔵の config.yml（初期値）。 */
    private FileConfiguration loadBundledDefaults() {
        try (InputStream in = getResource("config.yml")) {
            if (in == null) {
                return new YamlConfiguration();
            }
            return YamlConfiguration.loadConfiguration(new InputStreamReader(in, StandardCharsets.UTF_8));
        } catch (Exception e) {
            getLogger().warning("初期設定の読み込みに失敗しました: " + e.getMessage());
            return new YamlConfiguration();
        }
    }

    public Messages getMessages() {
        return messages;
    }

    public Sounds getSounds() {
        return sounds;
    }

    public ChestStore getStore() {
        return store;
    }

    public ShareInputListener getShareInput() {
        return shareInput;
    }

    public PvpRetaliationListener getPvpRetaliation() {
        return pvpRetaliation;
    }
}
