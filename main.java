

package multicore.all;

import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.event.Event;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.PluginManager;
import org.bukkit.plugin.java.JavaPlugin;

import java.lang.reflect.Method;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;
import java.util.stream.Collectors;

public class ALL extends JavaPlugin implements CommandExecutor, TabCompleter {

    private List<String> asyncPlugins;
    private List<String> excludedPlugins;
    private Set<String> excludedEvents;
    private int timeout;
    private List<AsyncListener> registeredListeners;
    private MultiThreadPool threadPool;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        registeredListeners = new ArrayList<>();
        excludedEvents = new HashSet<>();
        loadConfig();
        initializeThreadPool();
        wrapPlugins();

        getCommand("asyncwrapper").setExecutor(this);
        getCommand("asyncwrapper").setTabCompleter(this);
        monitorActiveTasksCount();
        getLogger().info("AsyncPluginWrapper has been enabled and configured.");
    }

    private void loadConfig() {
        reloadConfig();
        FileConfiguration config = getConfig();
        asyncPlugins = config.getStringList("非同期化対象プラグイン");
        excludedPlugins = config.getStringList("除外プラグイン");
        excludedEvents.addAll(config.getStringList("除外イベント"));

        String logLevel = config.getString("詳細設定.ログレベル", "INFO");
        setLogLevel(logLevel);

        timeout = config.getInt("詳細設定.タイムアウト", 5000);

        getLogger().info("設定を読み込みました。非同期化対象: " + asyncPlugins.size() +
                "個, 除外プラグイン: " + excludedPlugins.size() +
                "個, 除外イベント: " + excludedEvents.size() +
                "個, タイムアウト: " + timeout + "ms");
    }

    private void initializeThreadPool() {
        int highPriorityThreads = getConfig().getInt("thread_pools.high_priority", 2);
        int normalPriorityThreads = getConfig().getInt("thread_pools.normal_priority", 5);
        int lowPriorityThreads = getConfig().getInt("thread_pools.low_priority", 3);
        threadPool = new MultiThreadPool(highPriorityThreads, normalPriorityThreads, lowPriorityThreads);
    }

    private void setLogLevel(String logLevel) {
        Level level;
        switch (logLevel.toUpperCase()) {
            case "DEBUG":
                level = Level.FINE;
                break;
            case "WARNING":
                level = Level.WARNING;
                break;
            default:
                level = Level.INFO;
        }
        getLogger().setLevel(level);
    }

    private boolean shouldWrapPlugin(String pluginName) {
        return (asyncPlugins.contains("*") || asyncPlugins.contains(pluginName))
                && !excludedPlugins.contains(pluginName);
    }

    private void wrapPlugins() {
        PluginManager pluginManager = Bukkit.getPluginManager();
        for (Plugin plugin : pluginManager.getPlugins()) {
            if (plugin != this && shouldWrapPlugin(plugin.getName())) {
                wrapPluginEvents(plugin);
            }
        }
    }

    private void wrapPluginEvents(Plugin plugin) {
        for (Method method : plugin.getClass().getDeclaredMethods()) {
            if (method.isAnnotationPresent(org.bukkit.event.EventHandler.class)) {
                Class<? extends Event> eventClass = (Class<? extends Event>) method.getParameterTypes()[0];
                if (excludedEvents.contains(eventClass.getSimpleName())) {
                    getLogger().info(plugin.getName() + "の" + eventClass.getSimpleName() + "イベントは除外リストに含まれているため、非同期化しません。");
                    continue;
                }
                EventPriority priority = method.getAnnotation(org.bukkit.event.EventHandler.class).priority();
                boolean ignoreCancelled = method.getAnnotation(org.bukkit.event.EventHandler.class).ignoreCancelled();

                AsyncListener listener = new AsyncListener(plugin);
                registeredListeners.add(listener);

                Bukkit.getPluginManager().registerEvent(eventClass, listener, priority, (l, event) -> {
                    threadPool.submitTask(plugin, () -> {
                        try {
                            long startTime = System.currentTimeMillis();
                            method.invoke(plugin, event);
                            long elapsedTime = System.currentTimeMillis() - startTime;

                            if (elapsedTime > timeout) {
                                getLogger().warning(plugin.getName() + "の非同期処理がタイムアウトしました。 処理時間: " + elapsedTime + "ms");
                            }
                        } catch (Exception e) {
                            getLogger().severe(plugin.getName() + "の非同期イベント処理中にエラーが発生しました");
                            e.printStackTrace();
                        }
                    }, priority);
                }, this, ignoreCancelled);
            }
        }
        getLogger().info(plugin.getName() + "のイベントを非同期化しました");
    }

    public void monitorActiveTasksCount() {
        Bukkit.getScheduler().runTaskTimerAsynchronously(this, () -> {
            for (Plugin plugin : Bukkit.getPluginManager().getPlugins()) {
                int activeTasks = threadPool.getActiveTaskCount(plugin);
                if (activeTasks > 0) {
                    getLogger().info(plugin.getName() + "の現在のアクティブタスク数: " + activeTasks);
                }
            }
        }, 0L, 20L * 60); // 1分ごとに実行
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (command.getName().equalsIgnoreCase("asyncwrapper")) {
            if (args.length > 0) {
                switch (args[0].toLowerCase()) {
                    case "reload":
                        if (sender.hasPermission("asyncwrapper.reload")) {
                            reload();
                            sender.sendMessage("AsyncPluginWrapper の設定をリロードしました。");
                        } else {
                            sender.sendMessage("このコマンドを実行する権限がありません。");
                        }
                        return true;
                    case "exclude":
                        if (args.length > 1 && sender.hasPermission("asyncwrapper.exclude")) {
                            excludedEvents.add(args[1]);
                            saveExcludedEvents();
                            reload();
                            sender.sendMessage(args[1] + " イベントを除外リストに追加しました。");
                        } else {
                            sender.sendMessage("使用方法: /asyncwrapper exclude <イベント名>");
                        }
                        return true;
                    case "include":
                        if (args.length > 1 && sender.hasPermission("asyncwrapper.include")) {
                            excludedEvents.remove(args[1]);
                            saveExcludedEvents();
                            reload();
                            sender.sendMessage(args[1] + " イベントを除外リストから削除しました。");
                        } else {
                            sender.sendMessage("使用方法: /asyncwrapper include <イベント名>");
                        }
                        return true;
                    case "list":
                        if (sender.hasPermission("asyncwrapper.list")) {
                            sender.sendMessage("除外されているイベント: " + String.join(", ", excludedEvents));
                        } else {
                            sender.sendMessage("このコマンドを実行する権限がありません。");
                        }
                        return true;
                }
            }
        }
        return false;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (command.getName().equalsIgnoreCase("asyncwrapper")) {
            if (args.length == 1) {
                return Arrays.asList("reload", "exclude", "include", "list").stream()
                        .filter(s -> s.startsWith(args[0].toLowerCase()))
                        .collect(Collectors.toList());
            } else if (args.length == 2) {
                if (args[0].equalsIgnoreCase("exclude")) {
                    return getAllEvents().stream()
                            .filter(s -> s.startsWith(args[1]) && !excludedEvents.contains(s))
                            .collect(Collectors.toList());
                } else if (args[0].equalsIgnoreCase("include")) {
                    return excludedEvents.stream()
                            .filter(s -> s.startsWith(args[1]))
                            .collect(Collectors.toList());
                }
            }
        }
        return Collections.emptyList();
    }

    private List<String> getAllEvents() {
        return Arrays.stream(org.bukkit.event.Event.class.getDeclaredClasses())
                .map(Class::getSimpleName)
                .collect(Collectors.toList());
    }

    private void saveExcludedEvents() {
        FileConfiguration config = getConfig();
        config.set("除外イベント", new ArrayList<>(excludedEvents));
        saveConfig();
    }

    private void reload() {
        // 登録されているすべてのリスナーを解除
        for (AsyncListener listener : registeredListeners) {
            HandlerList.unregisterAll(listener);
        }
        registeredListeners.clear();

        // 設定を再読み込み
        loadConfig();

        // スレッドプールを再初期化
        if (threadPool != null) {
            threadPool.shutdown();
        }
        initializeThreadPool();

        // プラグインを再ラップ
        wrapPlugins();

        getLogger().info("AsyncPluginWrapper の設定をリロードしました。");
    }

    @Override
    public void onDisable() {
        // 登録されているすべてのリスナーを解除
        for (AsyncListener listener : registeredListeners) {
            HandlerList.unregisterAll(listener);
        }
        registeredListeners.clear();

        // スレッドプールのシャットダウン
        if (threadPool != null) {
            threadPool.shutdown();
        }

        getLogger().info("AsyncPluginWrapper has been disabled.");
    }

    private static class AsyncListener implements Listener {
        private final Plugin plugin;

        public AsyncListener(Plugin plugin) {
            this.plugin = plugin;
        }
    }

    private static class MultiThreadPool {
        private final ExecutorService highPriorityPool;
        private final ExecutorService normalPriorityPool;
        private final ExecutorService lowPriorityPool;
        private final ConcurrentMap<Plugin, AtomicInteger> activeTasksCount;

        public MultiThreadPool(int highPriorityThreads, int normalPriorityThreads, int lowPriorityThreads) {
            this.highPriorityPool = new ThreadPoolExecutor(highPriorityThreads, highPriorityThreads,
                    0L, TimeUnit.MILLISECONDS, new LinkedBlockingQueue<>(), new NamedThreadFactory("High-Priority"));
            this.normalPriorityPool = new ThreadPoolExecutor(normalPriorityThreads, normalPriorityThreads,
                    0L, TimeUnit.MILLISECONDS, new LinkedBlockingQueue<>(), new NamedThreadFactory("Normal-Priority"));
            this.lowPriorityPool = new ThreadPoolExecutor(lowPriorityThreads, lowPriorityThreads,
                    0L, TimeUnit.MILLISECONDS, new LinkedBlockingQueue<>(), new NamedThreadFactory("Low-Priority"));
            this.activeTasksCount = new ConcurrentHashMap<>();
        }

        public void submitTask(Plugin plugin, Runnable task, EventPriority priority) {
            AtomicInteger count = activeTasksCount.computeIfAbsent(plugin, k -> new AtomicInteger(0));
            count.incrementAndGet();

            Runnable wrappedTask = () -> {
                try {
                    task.run();
                } finally {
                    count.decrementAndGet();
                }
            };

            switch (priority) {
                case HIGHEST:
                case HIGH:
                    highPriorityPool.submit(wrappedTask);
                    break;
                case NORMAL:
                    normalPriorityPool.submit(wrappedTask);
                    break;
                case LOW:
                case LOWEST:
                    lowPriorityPool.submit(wrappedTask);
                    break;
                default:
                    normalPriorityPool.submit(wrappedTask);
            }
        }

        public void shutdown() {
            shutdownAndAwaitTermination(highPriorityPool);
            shutdownAndAwaitTermination(normalPriorityPool);
            shutdownAndAwaitTermination(lowPriorityPool);
        }

        private void shutdownAndAwaitTermination(ExecutorService pool) {
            pool.shutdown();
            try {
                if (!pool.awaitTermination(60, TimeUnit.SECONDS)) {
                    pool.shutdownNow();
                    if (!pool.awaitTermination(60, TimeUnit.SECONDS))
                        System.err.println("Pool did not terminate");
                }
            } catch (InterruptedException ie) {
                pool.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }

        public int getActiveTaskCount(Plugin plugin) {
            return activeTasksCount.getOrDefault(plugin, new AtomicInteger(0)).get();
        }

        private static class NamedThreadFactory implements ThreadFactory {
            private final String name;
            private final AtomicInteger threadNumber = new AtomicInteger(1);

            NamedThreadFactory(String name) {
                this.name = name;
            }

            @Override
            public Thread newThread(Runnable r) {
                Thread t = new Thread(r, name + "-Thread-" + threadNumber.getAndIncrement());
                t.setDaemon(true);
                return t;
            }
        }
    }
}