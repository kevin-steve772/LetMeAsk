package com.cubex.quiz;

import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.lang.reflect.Method;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Level;

/**
 * Quiz activity plugin (CubeX-compatible layout).
 * - Uses Vault for economy (recommended: CMI exposes Vault provider)
 * - Integrates with external HumanVerifyApi as in your example. If you have a different package
 *   for HumanVerifyApi, add the API dependency when compiling. If compilation fails, adjust imports
 *   to the correct package for your HumanVerify plugin.
 */
public class QuizPlugin extends JavaPlugin implements Listener {
    // Vault economy provider (kept as Object to avoid compile-time dependency on Vault API)
    private Object econ; // provider instance

    private final Random random = new Random();

    // runtime state
    private volatile Question currentQuestion = null;
    private volatile boolean paused = false; // paused due to payer insufficient funds
    private volatile boolean verifying = false; // question locked while human verification pending

    // config values
    private String payerName;
    private double rewardAmount;
    private long questionIntervalSeconds;
    private int antiBotThresholdSeconds;
    private int antiBotCorrectAnswerThreshold;
    private double fuzzySimilarityThreshold = 0.75; // default similarity threshold (0-1)

    // resolved payer information (support UUID / OfflinePlayer / Server / LittleSkin via prefix)
    private org.bukkit.OfflinePlayer payerOffline = null;
    private boolean payerIsServer = false;
    private boolean payerUsesUuid = false;
    private String payerDisplay = null; // human readable identifier

    // config files
    private File baseFile;
    private File questionsFile;
    private FileConfiguration baseCfg;
    private FileConfiguration questionsCfg;

    // scheduler handle
    private BukkitTask tickerTask;
    private final Map<java.util.UUID, Integer> correctAnswerCounts = new HashMap<>();

    @Override
    public void onEnable() {
        // Ensure default resource files exist
        saveResource("base.yml", false);
        saveResource("questions.yml", false);

        // load configuration files
        loadConfigValues();

        if (!setupEconomy()) {
            getLogger().severe("Vault economy not found — plugin will continue to load, but all payout logic is disabled until Vault is installed");
        }

        getServer().getPluginManager().registerEvents(this, this);

        // register command
        if (getCommand("letmeask") != null) {
            QuizCommand quizCommand = new QuizCommand();
            getCommand("letmeask").setExecutor(quizCommand);
            getCommand("letmeask").setTabCompleter(quizCommand);
        }

        // Start scheduler to post questions periodically (also checks paused state)
        startTask();

        getLogger().info("QuizPlugin enabled");
    }

    @Override
    public void onDisable() {
        stopTask();
        getLogger().info("QuizPlugin disabled");
    }

    private void loadConfigValues() {
        // load base and questions from their own files
        baseFile = new File(getDataFolder(), "base.yml");
        questionsFile = new File(getDataFolder(), "questions.yml");
        try {
            if (!baseFile.exists()) saveResource("base.yml", false);
            if (!questionsFile.exists()) saveResource("questions.yml", false);
        } catch (Exception ignored) {}

        baseCfg = YamlConfiguration.loadConfiguration(baseFile);
        questionsCfg = YamlConfiguration.loadConfiguration(questionsFile);

        payerName = baseCfg.getString("payer", "Server");
        rewardAmount = baseCfg.getDouble("reward", 50.0);
        questionIntervalSeconds = baseCfg.getLong("question-interval-seconds", 60L);
        antiBotThresholdSeconds = baseCfg.getInt("anti-bot-threshold-seconds", 1);
        antiBotCorrectAnswerThreshold = baseCfg.getInt("anti-bot-correct-answer-threshold", 3);
        fuzzySimilarityThreshold = baseCfg.getDouble("fuzzy-similarity-threshold", fuzzySimilarityThreshold);

        // resolve payer to a stable identifier (UUID/name/Server/LittleSkin)
        resolvePayer(payerName);

        List<String> raw = questionsCfg.getStringList("questions");
        if (raw == null || raw.isEmpty()) {
            raw = Arrays.asList(
                    "中国首都=北京",
                    "2+2=4",
                    "香蕉是什么颜色=黄色"
            );
            getLogger().warning("questions.yml 中没有题目，使用内置示例题目。请在 questions.yml 中配置 questions 字段（格式：题目=答案）");
        }

        questions.clear();
        for (String line : raw) {
            String[] parts = line.split("=", 2);
            if (parts.length < 2) parts = line.split(":", 2);
            if (parts.length >= 2) {
                questions.add(new Question(parts[0].trim(), parts[1].trim()));
            }
        }

        if (questions.isEmpty()) {
            getLogger().severe("没有可用题目，插件无法正常出题。请在 questions.yml 中添加题目。禁用插件。");
            getServer().getPluginManager().disablePlugin(this);
        }
    }

    private void startTask() {
        stopTask();
        tickerTask = new BukkitRunnable() {
            @Override
            public void run() {
                try {
                    tick();
                } catch (Throwable t) {
                    getLogger().log(Level.SEVERE, "Error in quiz tick", t);
                }
            }
        }.runTaskTimer(this, 20L, Math.max(1L, questionIntervalSeconds) * 20L);
    }

    private String messagePrefix() {
        String prefix = baseCfg == null ? "&6[教育部]" : baseCfg.getString("messages.prefix", "&6[教育部]");
        return prefix.replace('&', '§');
    }

    private void resolvePayer(String payer) {
        payerOffline = null;
        payerIsServer = false;
        payerUsesUuid = false;
        payerDisplay = payer;
        if (payer == null) return;
        if (payer.equalsIgnoreCase("server") || payer.equalsIgnoreCase("console")) {
            payerIsServer = true;
            payerDisplay = payer;
            return;
        }
        // support littleskin:uuid or littleskin:name
        if (payer.toLowerCase().startsWith("littleskin:")) {
            String v = payer.substring(payer.indexOf(":") + 1);
            try {
                java.util.UUID uuid = java.util.UUID.fromString(v);
                payerOffline = Bukkit.getOfflinePlayer(uuid);
                payerDisplay = uuid.toString();
                return;
            } catch (IllegalArgumentException ignored) {
                // fall through to name
            }
            payerOffline = Bukkit.getOfflinePlayer(v);
            payerDisplay = payerOffline.getName() != null ? payerOffline.getName() : v;
            return;
        }
        // try UUID
        try {
            java.util.UUID uuid = java.util.UUID.fromString(payer);
            payerOffline = Bukkit.getOfflinePlayer(uuid);
            payerUsesUuid = true;
            payerDisplay = uuid.toString();
            return;
        } catch (IllegalArgumentException ignored) {
        }
        // fallback to name
        payerOffline = Bukkit.getOfflinePlayer(payer);
        payerDisplay = payerOffline.getName() != null ? payerOffline.getName() : payer;
    }

    private void stopTask() {
        if (tickerTask != null && !tickerTask.isCancelled()) {
            tickerTask.cancel();
            tickerTask = null;
        }
    }

    private boolean postNewQuestion(boolean force) {
        if (!force && (currentQuestion != null || verifying || paused)) return false;
        Question q = questions.get(random.nextInt(questions.size()));
        currentQuestion = q;
        currentQuestion.postTime = System.currentTimeMillis();
        Bukkit.broadcastMessage(messagePrefix() + " §f新题目: §f" + q.question);
        return true;
    }

    private boolean matches(String provided, String answer) {
        if (provided == null || answer == null) return false;
        String a = normalize(provided);
        String b = normalize(answer);
        if (a.isEmpty() || b.isEmpty()) return false;
        if (a.equalsIgnoreCase(b)) return true;
        int dist = levenshtein(a, b);
        int max = Math.max(a.length(), b.length());
        double sim = 1.0 - (double) dist / (double) max;
        // Permit one typo in short answers, but not an omitted or extra character.
        if (dist == 1 && a.length() == b.length() && max >= 2) return true;
        return sim >= fuzzySimilarityThreshold || (dist <= 2 && max >= 4);
    }

    private String normalize(String s) {
        return s == null ? "" : s.replaceAll("[^\\p{L}\\p{N}]+", "").toLowerCase();
    }

    private int levenshtein(String s1, String s2) {
        int[] prev = new int[s2.length() + 1];
        int[] curr = new int[s2.length() + 1];
        for (int j = 0; j <= s2.length(); j++) prev[j] = j;
        for (int i = 1; i <= s1.length(); i++) {
            curr[0] = i;
            for (int j = 1; j <= s2.length(); j++) {
                int cost = s1.charAt(i - 1) == s2.charAt(j - 1) ? 0 : 1;
                curr[j] = Math.min(Math.min(curr[j - 1] + 1, prev[j] + 1), prev[j - 1] + cost);
            }
            System.arraycopy(curr, 0, prev, 0, prev.length);
        }
        return prev[s2.length()];
    }

    private final List<Question> questions = new ArrayList<>();

    private void tick() {
        // If paused, check payer balance; resume when enough funds
        if (paused) {
            double bal = getBalanceOf(payerDisplay);
            if (bal >= rewardAmount) {
                paused = false;
                Bukkit.broadcastMessage(messagePrefix() + " §a资金已足额，恢复出题。当前余额: " + bal);
            } else {
                // still paused
                return;
            }
        }

        // If a question is active or verifying is in progress, skip
        if (currentQuestion != null || verifying) return;

        // Post a new question
        Question q = questions.get(random.nextInt(questions.size()));
        currentQuestion = q;
        currentQuestion.postTime = System.currentTimeMillis();

        Bukkit.broadcastMessage(messagePrefix() + " §f新题目: §f" + q.question + " §6");
    }

    @EventHandler
    public void onPlayerChat(AsyncPlayerChatEvent event) {
        if (currentQuestion == null || verifying) return;

        String msg = event.getMessage().trim();
        Player player = event.getPlayer();

        if (matches(msg, currentQuestion.answer)) {
            // Mark answered synchronously to avoid race — switch to main thread
            event.setCancelled(false);
            // Handle on main thread
            Bukkit.getScheduler().runTask(this, () -> handleCorrectAnswer(player));
        }
    }

    private synchronized void handleCorrectAnswer(Player player) {
        if (currentQuestion == null || verifying) return; // double-check

        long now = System.currentTimeMillis();
        long deltaSecs = (now - currentQuestion.postTime) / 1000L;

        int correctAnswerCount = correctAnswerCounts.merge(player.getUniqueId(), 1, Integer::sum);
        boolean answeredTooFast = deltaSecs <= antiBotThresholdSeconds;
        boolean answeredTooOften = antiBotCorrectAnswerThreshold > 0
                && correctAnswerCount >= antiBotCorrectAnswerThreshold;

        // Invoke human verification for unusually fast or repeated correct answers.
        if (answeredTooFast || answeredTooOften) {
            verifying = true;
            Player p = player;
            String reason = answeredTooFast ? "答题速度过快" : "连续答对次数过多";
            Bukkit.broadcastMessage(messagePrefix() + " §c玩家 §f" + p.getName() + " §c"
                    + reason + "，需要进行人机验证...");

            // Try to call HumanVerifyApi as in provided snippet. This requires that the HumanVerify API
            // is available at compile/runtime. If you use a different package, add the dependency.
            try {
                // 使用反射调用 HumanVerifyApi，避免将第三方实现打进本插件。
                Class<?> apiClass = Class.forName("org.cubexmc.humanverify.api.HumanVerifyApi");
                Object api = Bukkit.getServicesManager().load((Class) apiClass);
                if (api != null) {
                    Object future = null;
                    try {
                        future = apiClass.getMethod("requestVerification", org.bukkit.entity.Player.class, boolean.class)
                                .invoke(api, p, true);
                    } catch (NoSuchMethodException nsme) {
                        getLogger().warning("HumanVerifyApi 没有 requestVerification(Player, boolean) 方法，验证失败，不发放奖励。");
                    }

                    if (future instanceof java.util.concurrent.CompletableFuture) {
                        ((java.util.concurrent.CompletableFuture<?>) future).thenAccept(result -> {
                            try {
                                // 通过比较枚举名称判断是否为 SUCCESS
                                boolean ok = false;
                                try {
                                    java.lang.reflect.Method nameM = result.getClass().getMethod("name");
                                    String nm = (String) nameM.invoke(result);
                                    ok = "SUCCESS".equals(nm);
                                } catch (Exception e) {
                                    // fallback to toString
                                    ok = "SUCCESS".equals(result.toString());
                                }

                                if (ok) {
                                    Bukkit.getScheduler().runTask(this, () -> {
                                        if (!p.isOnline()) {
                                            verifying = false;
                                            correctAnswerCounts.remove(p.getUniqueId());
                                            currentQuestion = null;
                                            return;
                                        }
                                        verifying = false;
                                        correctAnswerCounts.remove(p.getUniqueId());
                                        awardWinner(p);
                                        currentQuestion = null;
                                    });
                                } else {
                                    Bukkit.getScheduler().runTask(this, () -> {
                                        verifying = false;
                                        correctAnswerCounts.remove(p.getUniqueId());
                                        currentQuestion = null;
                                        Bukkit.broadcastMessage(messagePrefix() + " §c玩家 §f" + p.getName() + " §c未通过人机验证，已被踢出服务器。");
                                        p.kickPlayer("未通过人机验证");
                                    });
                                }
                            } catch (Throwable t) {
                                getLogger().log(Level.SEVERE, "处理人机验证结果时出错", t);
                                Bukkit.getScheduler().runTask(this, () -> {
                                    verifying = false;
                                    correctAnswerCounts.remove(p.getUniqueId());
                                    currentQuestion = null;
                                });
                            }
                        });
                    } else {
                        getLogger().warning("HumanVerifyApi.requestVerification 未返回 CompletableFuture 或返回 null，验证失败，不发放奖励");
                        verifying = false;
                        correctAnswerCounts.remove(p.getUniqueId());
                        currentQuestion = null;
                    }
                } else {
                    getLogger().warning("未能通过 ServicesManager 加载 HumanVerifyApi，验证失败，不发放奖励。");
                    verifying = false;
                    correctAnswerCounts.remove(p.getUniqueId());
                    currentQuestion = null;
                }
            } catch (ClassNotFoundException cnf) {
                getLogger().warning("HumanVerifyApi 类未找到，无法执行人机验证。请确认 HumanVerify 已安装并先于本插件加载。");
                verifying = false;
                correctAnswerCounts.remove(p.getUniqueId());
                currentQuestion = null;
            } catch (Throwable t) {
                getLogger().log(Level.SEVERE, "调用人机验证 API 时出错，验证失败，不发放奖励", t);
                verifying = false;
                correctAnswerCounts.remove(p.getUniqueId());
                currentQuestion = null;
            }

            return;
        }

        // Normal awarding
        awardWinner(player);
        currentQuestion = null;
    }

    private void awardWinner(Player winner) {
        // Check payer balance
        double payerBal = getBalanceOf(payerDisplay);
        if (payerBal < rewardAmount) {
            paused = true;
            Bukkit.broadcastMessage(messagePrefix() + " §c出题已暂停：资金不足（需要 " + rewardAmount + "，当前 " + payerBal + "）。");
            return;
        }

        Object w = withdrawFrom(payerDisplay, rewardAmount);
        if (!isEconomyResponseSuccess(w)) {
            paused = true;
            String err = getEconomyResponseError(w);
            Bukkit.broadcastMessage(messagePrefix() + " §c转账失败（错误: " + err + "），出题已暂停。请检查服务器日志。" );
            getLogger().warning("扣款失败: " + err);
            return;
        }

        Object d = depositTo(winner.getName(), rewardAmount);
        if (!isEconomyResponseSuccess(d)) {
            // refund payer if possible
            String err = getEconomyResponseError(d);
            getLogger().warning("发放给胜利玩家失败: " + err + "。尝试退款。");
            depositTo(payerDisplay, rewardAmount);
            Bukkit.broadcastMessage(messagePrefix() + " §c发放奖励失败，已退款，请联系管理员。错误: " + err);
            return;
        }

        Bukkit.broadcastMessage(messagePrefix() + " §a玩家 §f" + winner.getName() + " §a答对了问题，获得 §e" + rewardAmount + " §a货币！");
    }

    private boolean setupEconomy() {
        try {
            Class<?> econClass = Class.forName("net.milkbowl.vault.economy.Economy");
            // get registration via ServicesManager.getRegistration(Class)
            Object rsp = getServer().getServicesManager().getRegistration((Class) econClass);
            if (rsp == null) return false;
            // RegisteredServiceProvider has method getProvider()
            Method getProvider = rsp.getClass().getMethod("getProvider");
            Object provider = getProvider.invoke(rsp);
            this.econ = provider;
            return this.econ != null;
        } catch (ClassNotFoundException cnf) {
            getLogger().warning("Vault API 不在类路径中，无法加载 Economy 接口");
            return false;
        } catch (Throwable t) {
            getLogger().log(Level.SEVERE, "加载经济提供者时出错", t);
            return false;
        }
    }

    // Reflection helpers for interacting with economy provider without compile-time Vault dependency
    private double getBalanceOf(String who) {
        if (econ == null) return 0.0;
            if (payerIsServer) {
            Double balance = extractBalance(invokeEconomy("bankBalance", new Class<?>[]{String.class}, who));
            if (balance != null) return balance;
        }
        if (payerUsesUuid && payerOffline != null) {
            try {
                Object response = invokeEconomy("getBalance", new Class<?>[]{org.bukkit.OfflinePlayer.class}, payerOffline);
                if (response instanceof Number) return ((Number) response).doubleValue();
            } catch (Throwable t) {
                getLogger().fine("CMI OfflinePlayer 余额查询失败，回退到账户名: " + t.getMessage());
            }
        }
        try {
            Object response = invokeEconomy("getBalance", new Class<?>[]{String.class}, who);
            if (response instanceof Number) return ((Number) response).doubleValue();
        } catch (Throwable t) {
            getLogger().log(Level.WARNING, "查询账户 " + who + " 余额时出错", t);
        }
        if (payerOffline != null) {
            Object response = invokeEconomy("getBalance", new Class<?>[]{org.bukkit.OfflinePlayer.class}, payerOffline);
            if (response instanceof Number) return ((Number) response).doubleValue();
        }
        return 0.0;
    }

    private Object withdrawFrom(String who, double amount) {
        if (econ == null) return null;
        if (payerIsServer) {
            Object response = invokeEconomy("bankWithdraw", new Class<?>[]{String.class, double.class}, who, amount);
            if (response != null) return response;
        }
        if (payerUsesUuid && payerOffline != null) {
            try {
                Object response = invokeEconomy("withdrawPlayer", new Class<?>[]{org.bukkit.OfflinePlayer.class, double.class}, payerOffline, amount);
                if (response != null) return response;
            } catch (Throwable t) {
                getLogger().fine("CMI OfflinePlayer 扣款失败，回退到账户名: " + t.getMessage());
            }
        }
        try {
            Object response = invokeEconomy("withdrawPlayer", new Class<?>[]{String.class, double.class}, who, amount);
            if (response != null) return response;
        } catch (Throwable t) {
            getLogger().log(Level.WARNING, "从账户 " + who + " 扣款时出错", t);
        }
        if (payerOffline != null) {
            Object response = invokeEconomy("withdrawPlayer", new Class<?>[]{org.bukkit.OfflinePlayer.class, double.class}, payerOffline, amount);
            if (response != null) return response;
        }
        return null;
    }

    private Object depositTo(String who, double amount) {
        if (econ == null) return null;
        try {
            if (payerIsServer && who.equals(payerDisplay)) {
                Object bankResponse = invokeEconomy("bankDeposit", new Class<?>[]{String.class, double.class}, who, amount);
                if (bankResponse != null) return bankResponse;
            }
            if (payerUsesUuid && who != null && payerOffline != null && payerOffline.getName() != null && payerOffline.getName().equals(who)) {
                // deposit to offline payer
                try {
                    Object response = invokeEconomy("depositPlayer", new Class<?>[]{org.bukkit.OfflinePlayer.class, double.class}, payerOffline, amount);
                    if (response != null) return response;
                } catch (Throwable ignored) {}
            }
            try {
                Object response = invokeEconomy("depositPlayer", new Class<?>[]{String.class, double.class}, who, amount);
                if (response != null) return response;
            } catch (Throwable ignored) {}
        } catch (Throwable t) {
            getLogger().log(Level.WARNING, "给账户充值时出错", t);
        }
        return null;
    }

    private Object invokeEconomy(String methodName, Class<?>[] parameterTypes, Object... arguments) {
        try {
            Class<?> economyInterface = Class.forName("net.milkbowl.vault.economy.Economy");
            return economyInterface.getMethod(methodName, parameterTypes).invoke(econ, arguments);
        } catch (NoSuchMethodException ignored) {
            return null;
        } catch (Throwable t) {
            getLogger().log(Level.WARNING, "调用经济方法 " + methodName + " 时出错", t);
            return null;
        }
    }

    private Double extractBalance(Object response) {
        if (response instanceof Number) return ((Number) response).doubleValue();
        if (response == null) return null;
        try {
            try {
                Method method = response.getClass().getMethod("getBalance");
                Object value = method.invoke(response);
                if (value instanceof Number) return ((Number) value).doubleValue();
            } catch (NoSuchMethodException ignored) {
                java.lang.reflect.Field field = response.getClass().getField("balance");
                Object value = field.get(response);
                if (value instanceof Number) return ((Number) value).doubleValue();
            }
        } catch (Throwable t) {
            getLogger().log(Level.WARNING, "读取经济余额响应时出错", t);
        }
        return null;
    }

    private boolean isEconomyResponseSuccess(Object resp) {
        if (resp == null) return false;
        try {
            // try transactionSuccess() method
            try {
                Method m = resp.getClass().getMethod("transactionSuccess");
                Object r = m.invoke(resp);
                if (r instanceof Boolean) return (Boolean) r;
            } catch (NoSuchMethodException ignored) {}
            // try success field
            try {
                java.lang.reflect.Field f = resp.getClass().getField("success");
                Object r = f.get(resp);
                if (r instanceof Boolean) return (Boolean) r;
            } catch (NoSuchFieldException ignored) {}
        } catch (Throwable t) {
            getLogger().log(Level.WARNING, "检查经济响应时出错", t);
        }
        return false;
    }

    private String getEconomyResponseError(Object resp) {
        if (resp == null) return "null_response";
        try {
            // try errorMessage field
            try {
                java.lang.reflect.Field f = resp.getClass().getField("errorMessage");
                Object r = f.get(resp);
                if (r != null) return r.toString();
            } catch (NoSuchFieldException ignored) {}
            // try getErrorMessage() method
            try {
                Method m = resp.getClass().getMethod("getErrorMessage");
                Object r = m.invoke(resp);
                if (r != null) return r.toString();
            } catch (NoSuchMethodException ignored) {}
            // try toString()
            return resp.toString();
        } catch (Throwable t) {
            getLogger().log(Level.WARNING, "读取经济响应错误信息时出错", t);
            return "error_read_failed";
        }
    }

    // Command handler
    private class QuizCommand implements CommandExecutor, TabCompleter {
        @Override
        public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
            if (!sender.hasPermission("letmeask.admin")) {
                sender.sendMessage("§c你没有权限执行此命令 (letmeask.admin)");
                return true;
            }
            if (args.length == 0) {
                sender.sendMessage("§6LetMeAsk 指令： /letmeask start|stop|question [force]|reload|status");
                return true;
            }
            String sub = args[0].toLowerCase();
            switch (sub) {
                case "start":
                    startTask();
                    sender.sendMessage("§a已启动定时出题");
                    return true;
                case "stop":
                    stopTask();
                    sender.sendMessage("§c已停止定时出题");
                    return true;
                case "question":
                case "q": {
                    boolean force = args.length > 1 && args[1].equalsIgnoreCase("force");
                    boolean ok = postNewQuestion(force);
                    if (ok) sender.sendMessage("§a已发布新题目");
                    else sender.sendMessage("§c无法发布新题目（已有题目/正在验证/已暂停）。使用 /letmeask question force 可强制发布");
                    return true;
                }
                case "reload":
                    loadConfigValues();
                    // restart scheduler to pick up interval changes
                    startTask();
                    sender.sendMessage("§a已重载配置(base.yml 与 questions.yml)");
                    return true;
                case "status":
                    sender.sendMessage("§6LetMeAsk 状态:");
                    sender.sendMessage(" 自动出题: " + (tickerTask != null ? "§a运行中" : "§c已停止"));
                    sender.sendMessage(" 当前题目: " + (currentQuestion != null ? currentQuestion.question : "无"));
                    sender.sendMessage(" 暂停(余额不足): " + (paused ? "§c是" : "§a否"));
                    sender.sendMessage(" 人机验证锁定: " + (verifying ? "§c是" : "§a否"));
                    sender.sendMessage(" 支付玩家: §f" + payerDisplay + " §7(余额: " + String.format("%.2f", getBalanceOf(payerDisplay)) + ")");
                    return true;
                default:
                    sender.sendMessage("§c未知子命令: " + sub);
                    return true;
            }
        }

        @Override
        public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
            if (!sender.hasPermission("letmeask.admin")) return Collections.emptyList();

            if (args.length == 1) {
                String prefix = args[0].toLowerCase(Locale.ROOT);
                return Arrays.asList("start", "stop", "question", "q", "reload", "status").stream()
                        .filter(subcommand -> subcommand.startsWith(prefix))
                        .collect(java.util.stream.Collectors.toList());
            }

            if (args.length == 2 && (args[0].equalsIgnoreCase("question") || args[0].equalsIgnoreCase("q"))) {
                String prefix = args[1].toLowerCase(Locale.ROOT);
                return "force".startsWith(prefix) ? Collections.singletonList("force") : Collections.emptyList();
            }

            return Collections.emptyList();
        }
    }

    // Simple question holder
    private static class Question {
        final String question;
        final String answer;
        volatile long postTime;

        Question(String q, String a) {
            this.question = q;
            this.answer = a;
        }
    }
}
