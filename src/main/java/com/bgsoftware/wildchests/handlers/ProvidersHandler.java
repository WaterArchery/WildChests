package com.bgsoftware.wildchests.handlers;

import com.bgsoftware.wildchests.WildChestsPlugin;
import com.bgsoftware.wildchests.api.handlers.ProvidersManager;
import com.bgsoftware.wildchests.api.hooks.BankProvider;
import com.bgsoftware.wildchests.api.hooks.PricesProvider;
import com.bgsoftware.wildchests.api.hooks.StackerProvider;
import com.bgsoftware.wildchests.api.objects.DepositMethod;
import com.bgsoftware.wildchests.hooks.BankProvider_SuperiorSkyblock;
import com.bgsoftware.wildchests.hooks.BankProvider_Vault;
import com.bgsoftware.wildchests.hooks.PricesProvider_Default;
import com.bgsoftware.wildchests.hooks.PricesProvider_Essentials;
import com.bgsoftware.wildchests.hooks.PricesProvider_QuantumShop;
import com.bgsoftware.wildchests.hooks.PricesProvider_ShopGUIPlus;
import com.bgsoftware.wildchests.hooks.PricesProvider_zShop;
import com.bgsoftware.wildchests.hooks.StackerProvider_Default;
import com.bgsoftware.wildchests.hooks.StackerProvider_WildStacker;
import com.bgsoftware.wildchests.hooks.SuperiorSkyblockHook;
import com.bgsoftware.wildchests.utils.Executor;
import net.brcdev.shopgui.player.PlayerData;
import net.brcdev.shopgui.shop.Shop;
import net.brcdev.shopgui.shop.ShopItem;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.BiConsumer;
import java.util.function.Predicate;

@SuppressWarnings({"WeakerAccess", "unused"})
public final class ProvidersHandler implements ProvidersManager {

    private final WildChestsPlugin plugin;

    private final Map<DepositMethod, BankProvider> bankProviderMap = new EnumMap<>(DepositMethod.class);
    private final Map<UUID, PendingTransaction> pendingTransactions = new HashMap<>();

    private PricesProvider pricesProvider = new PricesProvider_Default();
    private StackerProvider stackerProvider = new StackerProvider_Default();
    private BankProvider customBankProvider = null;

    public ProvidersHandler(WildChestsPlugin plugin){
        this.plugin = plugin;

        Executor.sync(() -> {
            registerPricesProvider(plugin);
            registerStackersProvider();
            registerBanksProvider();

            if(bankProviderMap.isEmpty() && customBankProvider == null){
                WildChestsPlugin.log("");
                WildChestsPlugin.log("If you want sell-chests to be enabled, please install Vault & Economy plugin.");
                WildChestsPlugin.log("");
            }

            if(Bukkit.getPluginManager().isPluginEnabled("SuperiorSkyblock2"))
                SuperiorSkyblockHook.register(plugin);

            if(Bukkit.getPluginManager().isPluginEnabled("ChestShop"))
                registerHook("ChestShopHook");
        });
    }

    @Override
    public void setPricesProvider(PricesProvider pricesProvider) {
        this.pricesProvider = pricesProvider;
    }

    @Override
    public void setStackerProvider(StackerProvider stackerProvider) {
        this.stackerProvider = stackerProvider;
    }

    @Override
    public void setBanksProvider(BankProvider banksProvider) {
        this.customBankProvider = banksProvider;
    }

    /*
     * Hooks' methods
     */

    public double getPrice(OfflinePlayer offlinePlayer, ItemStack itemStack){
        return pricesProvider.getPrice(offlinePlayer, itemStack);
    }

    public int getItemAmount(Item item) {
        return stackerProvider.getItemAmount(item);
    }

    public void setItemAmount(Item item, int amount) {
        stackerProvider.setItemAmount(item, amount);
    }

    public boolean dropItem(Location location, ItemStack itemStack, int amount) {
        return stackerProvider.dropItem(location, itemStack, amount);
    }

    /*
     * Handler's methods
     */

    public TransactionResult<Double> canSellItem(OfflinePlayer offlinePlayer, ItemStack itemStack){
        double price = itemStack == null ? 0 : getPrice(offlinePlayer, itemStack);
        return TransactionResult.of(price, _price -> _price > 0);
    }

    public boolean withdrawPlayer(OfflinePlayer offlinePlayer, double money){
        BankProvider vaultProvider = bankProviderMap.get(DepositMethod.VAULT);

        if(vaultProvider == null)
            return false;

        return ((BankProvider_Vault) vaultProvider).withdrawPlayer(offlinePlayer, money);
    }

    public void startSellingTask(OfflinePlayer offlinePlayer){
        if(!pendingTransactions.containsKey(offlinePlayer.getUniqueId()))
            pendingTransactions.put(offlinePlayer.getUniqueId(), new PendingTransaction());
    }

    public void stopSellingTask(OfflinePlayer offlinePlayer){
        PendingTransaction pendingTransaction = pendingTransactions.remove(offlinePlayer.getUniqueId());
        if(pendingTransaction != null)
            pendingTransaction.forEach(((depositMethod, value) -> depositPlayer(offlinePlayer, depositMethod, value)));
    }

    public boolean depositPlayer(OfflinePlayer offlinePlayer, DepositMethod depositMethod, double money){
        BankProvider bankProvider = bankProviderMap.getOrDefault(depositMethod, customBankProvider);

        if(bankProvider == null)
            return false;

        PendingTransaction pendingTransaction = pendingTransactions.get(offlinePlayer.getUniqueId());

        if(pendingTransaction != null){
            pendingTransaction.depositMoney(depositMethod, money);
            return true;
        }

        return bankProvider.depositMoney(offlinePlayer, BigDecimal.valueOf(money));
    }

    public void depositAllPending(){
        pendingTransactions.forEach((uuid, pendingTransaction) -> {
            OfflinePlayer offlinePlayer = Bukkit.getOfflinePlayer(uuid);
            pendingTransaction.forEach((depositMethod, value) -> depositPlayer(offlinePlayer, depositMethod, value));
        });
        pendingTransactions.clear();
    }

    private void registerPricesProvider(WildChestsPlugin plugin) {
        if(!(pricesProvider instanceof PricesProvider_Default))
            return;

        switch (plugin.getSettings().pricesProvider.toUpperCase()) {
            case "SHOPGUIPLUS":
                if (Bukkit.getPluginManager().isPluginEnabled("ShopGUIPlus")) {
                    try {
                        //noinspection JavaReflectionMemberAccess
                        ShopItem.class.getMethod("getSellPriceForAmount", Shop.class, Player.class, PlayerData.class, int.class);
                        pricesProvider = (PricesProvider) Class.forName("com.bgsoftware.wildchests.hooks.PricesProvider_ShopGUIPlusOld").newInstance();
                    } catch (Throwable ex) {
                        pricesProvider = new PricesProvider_ShopGUIPlus();
                    }
                    break;
                }
            case "QUANTUMSHOP":
                if (Bukkit.getPluginManager().isPluginEnabled("QuantumShop")) {
                    pricesProvider = new PricesProvider_QuantumShop();
                    break;
                }
            case "ESSENTIALS":
                if (Bukkit.getPluginManager().isPluginEnabled("Essentials")) {
                    pricesProvider = new PricesProvider_Essentials();
                    break;
                }
            case "ZSHOP":
                if (Bukkit.getPluginManager().isPluginEnabled("zShop")) {
                    pricesProvider = new PricesProvider_zShop();
                    break;
                }
            default:
                WildChestsPlugin.log("- Couldn''t find any prices providers, using default one");
        }

    }

    private void registerStackersProvider() {
        if(!(stackerProvider instanceof StackerProvider_Default))
            return;

        if(Bukkit.getPluginManager().isPluginEnabled("WildStacker"))
            setStackerProvider(new StackerProvider_WildStacker());
    }

    private void registerBanksProvider() {
        if(Bukkit.getPluginManager().isPluginEnabled("Vault"))
            bankProviderMap.put(DepositMethod.VAULT, new BankProvider_Vault());

        if(Bukkit.getPluginManager().isPluginEnabled("SuperiorSkyblock2"))
            bankProviderMap.put(DepositMethod.SUPERIORSKYBLOCK2, new BankProvider_SuperiorSkyblock());
    }

    private void registerHook(String className) {
        try {
            Class<?> clazz = Class.forName("com.bgsoftware.wildchests.hooks." + className);
            Method registerMethod = clazz.getMethod("register", WildChestsPlugin.class);
            registerMethod.invoke(null, plugin);
        } catch (Exception ignored) {
        }
    }

    private <T> Optional<T> createInstance(String className) {
        try {
            Class<?> clazz = Class.forName("com.bgsoftware.superiorskyblock.hooks.provider." + className);
            try {
                Method compatibleMethod = clazz.getDeclaredMethod("isCompatible");
                if (!(boolean) compatibleMethod.invoke(null))
                    return Optional.empty();
            } catch (Exception ignored) {
            }

            try {
                Constructor<?> constructor = clazz.getConstructor(WildChestsPlugin.class);
                // noinspection unchecked
                return Optional.of((T) constructor.newInstance(plugin));
            } catch (Exception error) {
                // noinspection unchecked
                return Optional.of((T) clazz.newInstance());
            }
        } catch (ClassNotFoundException ignored) {
            return Optional.empty();
        } catch (Exception error) {
            error.printStackTrace();
            return Optional.empty();
        }
    }

    public static final class TransactionResult<T>{

        private final T data;
        private final Predicate<T> success;

        private TransactionResult(T data, Predicate<T> success){
            this.data = data;
            this.success = success;
        }

        public boolean isSuccess(){
            return success == null || success.test(data);
        }

        public T getData(){
            return data;
        }

        public static <T> TransactionResult<T> of(T data, Predicate<T> success){
            return new TransactionResult<>(data, success);
        }

    }

    private static final class PendingTransaction {

        private final Map<DepositMethod, MutableDouble> pendingDeposits = new EnumMap<>(DepositMethod.class);

        void depositMoney(DepositMethod depositMethod, double money) {
            pendingDeposits.computeIfAbsent(depositMethod, d -> new MutableDouble()).value += money;
        }

        void forEach(BiConsumer<DepositMethod, Double> consumer) {
            for(Map.Entry<DepositMethod, MutableDouble> entry : pendingDeposits.entrySet())
                consumer.accept(entry.getKey(), entry.getValue().value);
        }

        private static final class MutableDouble{

            private double value = 0;

        }

    }

}
