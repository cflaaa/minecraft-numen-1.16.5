package com.dwinovo.numen.core.data;

/**
 * 模组会发出去的翻译键目录（单一出处）。照上游 {@code data/ModLanguageData.java} 搬——
 * 上游那份是给两个加载器的数据生成器共用的完整目录（GUI/设置页/召唤页/provider 库等全部键，
 * 共 1400+ 行），并配有 {@code addEn}/{@code addZh} 两个喂给 datagen 的方法。
 *
 * <p><b>阶段 1 只搬权限层归位用到的那一节</b>：{@code PERMISSION_*}（规则给主人看的自述）、
 * {@code CONSENT_*}（征询答复卡片）与 {@code COMMAND_CONSENT_*}（征询命令的回话）。
 * 其余键属于各自的归位阶段（G 面板、provider 设置、召唤页），随那些阶段补进来；
 * 上游的 datagen 方法（{@code addEn}/{@code addZh}）等本仓有数据生成设施时一并搬。
 * 键名与文案逐字照抄上游，两个语言文件（{@code assets/numen/lang/*.json}）由这份目录誊写。
 *
 * <p>加新键：在 {@link Keys} 里加常量，并在 {@link #addEn}/{@code addZh}（即 {@link #addTranslations}）
 * 与两个 lang json 里同步文案（上游是跑 datagen 生成）。
 */
public final class ModLanguageData {

    private ModLanguageData() {}

    /** Sink for the loader-specific data generator's translation builder. */
    @FunctionalInterface
    public interface Adder {
        void add(String key, String value);
    }

    /**
     * 按语言把本目录的键喂给一个接收方（上游同名方法：数据生成器与命令测试都走它）。
     * 阶段 1 只有权限/征询这一节的文案；其余键随各自归位阶段补进 {@link #addEn}/{@code addZh}。
     */
    public static void addTranslations(String locale, Adder adder) {
        switch (locale) {
            case "en_us" -> addEn(adder);
            case "zh_cn" -> addZh(adder);
            default -> { }
        }
    }

    private static void addEn(Adder adder) {
        adder.add(Keys.PERMISSION_SEPARATOR, ", ");
        adder.add(Keys.PERMISSION_A_BLOCK, "a block");
        adder.add(Keys.PERMISSION_PLACED_BY, "placed by %s");
        adder.add(Keys.PERMISSION_UNCOVERED, "no rule");
        adder.add(Keys.PERMISSION_SIGNAL_PREFIX + "placed", "placed by a player");
        adder.add(Keys.PERMISSION_SIGNAL_PREFIX + "self_placed", "placed by herself");
        adder.add(Keys.PERMISSION_SIGNAL_PREFIX + "block_entity", "functional block");
        adder.add(Keys.PERMISSION_SIGNAL_PREFIX + "contents", "has things inside");
        adder.add(Keys.PERMISSION_SIGNAL_PREFIX + "owned", "has an owner");
        adder.add(Keys.PERMISSION_SIGNAL_PREFIX + "self_owned", "owned by herself");
        adder.add(Keys.PERMISSION_SIGNAL_PREFIX + "named", "named");
        adder.add(Keys.PERMISSION_SIGNAL_PREFIX + "villager", "villager");
        adder.add(Keys.PERMISSION_SIGNAL_PREFIX + "hostile", "hostile");
        adder.add(Keys.PERMISSION_SIGNAL_PREFIX + "hazard_item", "dangerous");
        adder.add(Keys.CONSENT_ALLOW, "Allow");
        adder.add(Keys.CONSENT_ALLOW_REMEMBER, "Always allow");
        adder.add(Keys.CONSENT_DENY, "Deny");
        adder.add(Keys.CONSENT_DENY_NOTE, "Deny with a note");
        adder.add(Keys.CONSENT_NOTE_ROW, "No, tell %s what to do instead…");
        adder.add(Keys.CONSENT_ASKING, "%s asks for your consent — press [%s] to answer");
        adder.add(Keys.CONSENT_WITHDRAWN, "%s's request went away: %s");
        adder.add("numen.consent.gone.owner_absent", "no answer came in time");
        adder.add("numen.consent.gone.superseded", "a newer request replaced it");
        adder.add("numen.consent.gone.unneeded", "it no longer needs your answer");
        adder.add("numen.consent.gone.task_ended", "the task that asked has ended");
        adder.add("numen.consent.gone.owner_stopped", "you pressed Stop");
        adder.add("numen.consent.gone.body_left", "she left the world");
        adder.add("numen.consent.gone.died", "she died");
        adder.add(Keys.CONSENT_CHOSE, "You chose “%s”");
        adder.add(Keys.CONSENT_DENIED_SAYING, "You said no: %s");
        adder.add(Keys.CONSENT_ANSWERED_ELSEWHERE, "Answered elsewhere");
        adder.add(Keys.CONSENT_VERB_PREFIX + "break", "break");
        adder.add(Keys.CONSENT_VERB_PREFIX + "place", "place");
        adder.add(Keys.CONSENT_VERB_PREFIX + "attack", "attack");
        adder.add(Keys.CONSENT_VERB_PREFIX + "use_block", "use");
        adder.add(Keys.CONSENT_VERB_PREFIX + "use_entity", "use");
        adder.add(Keys.CONSENT_VERB_PREFIX + "take", "take from");
        adder.add(Keys.CONSENT_VERB_PREFIX + "drop", "drop");
        adder.add(Keys.CONSENT_VERB_PREFIX + "command", "run");
        adder.add(Keys.CONSENT_ASK, "May I %s?");
        adder.add(Keys.CONSENT_ASK_MANY, "May I do these?");
        adder.add(Keys.CONSENT_SECONDS, "%ss");
        adder.add(Keys.COMMAND_CONSENT_ANSWERED, "Answered consent request #%s for %s: %s");
        adder.add(Keys.COMMAND_CONSENT_NOT_OWNER, "Consent request #%s belongs to a companion that is not yours");
        adder.add(Keys.COMMAND_CONSENT_NOT_PENDING, "No pending consent request #%s (already answered, expired or replaced)");
        adder.add(Keys.COMMAND_YOURS_NOT_HERE, "No companion of yours named '%s' is here");
        adder.add(Keys.COMMAND_MODE, "%s: permission mode %s");
        adder.add(Keys.COMMAND_MODE_SET, "%s: permission mode set to %s");
        adder.add(Keys.COMMAND_RULES_YOURS, "Your rules (checked first, deny → allow → ask):");
        adder.add(Keys.COMMAND_RULES_FACTORY, "Factory rules (checked after yours, allow → ask; not editable):");
        adder.add(Keys.COMMAND_RULES_NONE, "(none)");
        adder.add(Keys.COMMAND_RULES_UNMATCHED, "Nothing matched → ask.");
        adder.add(Keys.COMMAND_RULE_ADDED, "Added to %s: %s");
        adder.add(Keys.COMMAND_RULE_EXISTS, "%s already has: %s");
        adder.add(Keys.COMMAND_RULE_NO_ROW, "%s has no row %s (it has %s); /numen permission rules list shows the numbers");
        adder.add(Keys.COMMAND_RULE_REMOVED, "Removed from %s: %s");
        adder.add(Keys.COMMAND_RULES_RESET, "Cleared your deny, ask and allow tables; the factory rules still apply");
    }

    private static void addZh(Adder adder) {
        adder.add(Keys.PERMISSION_SEPARATOR, "、");
        adder.add(Keys.PERMISSION_A_BLOCK, "方块");
        adder.add(Keys.PERMISSION_PLACED_BY, "%s 放的");
        adder.add(Keys.PERMISSION_UNCOVERED, "没有规则");
        adder.add(Keys.PERMISSION_SIGNAL_PREFIX + "placed", "玩家放的");
        adder.add(Keys.PERMISSION_SIGNAL_PREFIX + "self_placed", "她自己放的");
        adder.add(Keys.PERMISSION_SIGNAL_PREFIX + "block_entity", "功能方块");
        adder.add(Keys.PERMISSION_SIGNAL_PREFIX + "contents", "装着东西");
        adder.add(Keys.PERMISSION_SIGNAL_PREFIX + "owned", "有主人");
        adder.add(Keys.PERMISSION_SIGNAL_PREFIX + "self_owned", "主人是她自己");
        adder.add(Keys.PERMISSION_SIGNAL_PREFIX + "named", "起了名字");
        adder.add(Keys.PERMISSION_SIGNAL_PREFIX + "villager", "村民");
        adder.add(Keys.PERMISSION_SIGNAL_PREFIX + "hostile", "敌对");
        adder.add(Keys.PERMISSION_SIGNAL_PREFIX + "hazard_item", "危险");
        adder.add(Keys.CONSENT_ALLOW, "允许");
        adder.add(Keys.CONSENT_ALLOW_REMEMBER, "以后都允许");
        adder.add(Keys.CONSENT_DENY, "拒绝");
        adder.add(Keys.CONSENT_DENY_NOTE, "说一句再拒绝");
        adder.add(Keys.CONSENT_NOTE_ROW, "不行,告诉 %s 该怎么做…");
        adder.add(Keys.CONSENT_ASKING, "%s 请求你的同意,按 [%s] 答复");
        adder.add(Keys.CONSENT_WITHDRAWN, "%s 的请求撤回了:%s");
        adder.add("numen.consent.gone.owner_absent", "没等到你的答复");
        adder.add("numen.consent.gone.superseded", "她又问了新的一条");
        adder.add("numen.consent.gone.unneeded", "不用再问你了");
        adder.add("numen.consent.gone.task_ended", "发起它的任务已经收场");
        adder.add("numen.consent.gone.owner_stopped", "你按了停止");
        adder.add("numen.consent.gone.body_left", "她离开了世界");
        adder.add("numen.consent.gone.died", "她死了");
        adder.add(Keys.CONSENT_CHOSE, "你选了「%s」");
        adder.add(Keys.CONSENT_DENIED_SAYING, "你拒绝了:%s");
        adder.add(Keys.CONSENT_ANSWERED_ELSEWHERE, "已在别处答复");
        adder.add(Keys.CONSENT_VERB_PREFIX + "break", "挖");
        adder.add(Keys.CONSENT_VERB_PREFIX + "place", "放");
        adder.add(Keys.CONSENT_VERB_PREFIX + "attack", "打");
        adder.add(Keys.CONSENT_VERB_PREFIX + "use_block", "用");
        adder.add(Keys.CONSENT_VERB_PREFIX + "use_entity", "用");
        adder.add(Keys.CONSENT_VERB_PREFIX + "take", "拿");
        adder.add(Keys.CONSENT_VERB_PREFIX + "drop", "丢");
        adder.add(Keys.CONSENT_VERB_PREFIX + "command", "执行");
        adder.add(Keys.CONSENT_ASK, "想%s,可以吗?");
        adder.add(Keys.CONSENT_ASK_MANY, "想做这几件事,可以吗?");
        adder.add(Keys.CONSENT_SECONDS, "%s 秒");
        adder.add(Keys.COMMAND_CONSENT_ANSWERED, "已答复征询 #%s(%s):%s");
        adder.add(Keys.COMMAND_CONSENT_NOT_OWNER, "征询 #%s 属于别人的同伴");
        adder.add(Keys.COMMAND_CONSENT_NOT_PENDING, "没有待答复的征询 #%s(已答复、过期或被顶掉)");
        adder.add(Keys.COMMAND_YOURS_NOT_HERE, "你叫「%s」的同伴不在这里");
        adder.add(Keys.COMMAND_MODE, "%s:权限模式 %s");
        adder.add(Keys.COMMAND_MODE_SET, "%s:权限模式已设为 %s");
        adder.add(Keys.COMMAND_RULES_YOURS, "你的规则(先查,deny → allow → ask):");
        adder.add(Keys.COMMAND_RULES_FACTORY, "出厂规则(在你的之后查,allow → ask;不能改):");
        adder.add(Keys.COMMAND_RULES_NONE, "(无)");
        adder.add(Keys.COMMAND_RULES_UNMATCHED, "都没中 → 问。");
        adder.add(Keys.COMMAND_RULE_ADDED, "已加进 %s:%s");
        adder.add(Keys.COMMAND_RULE_EXISTS, "%s 里已经有:%s");
        adder.add(Keys.COMMAND_RULE_NO_ROW, "%s 没有第 %s 行(一共 %s 行);行号用 /numen permission rules list 看");
        adder.add(Keys.COMMAND_RULE_REMOVED, "已从 %s 删掉:%s");
        adder.add(Keys.COMMAND_RULES_RESET, "清空了你的 deny、ask、allow 三张表;出厂规则照旧生效");
    }

    /** 翻译键目录。运行期代码引用这里，不写裸字符串键。 */
    public static final class Keys {
        private Keys() {}

        // ==================== 权限层（permission） ====================

        /** 清单里两件事之间的分隔。 */
        public static final String PERMISSION_SEPARATOR   = "numen.permission.separator";
        /** 说不出谁放的时候对一格方块的称呼。 */
        public static final String PERMISSION_A_BLOCK     = "numen.permission.a_block";
        /** 「谁谁放的」(%s 是玩家名)。 */
        public static final String PERMISSION_PLACED_BY   = "numen.permission.placed_by";
        /** 没有任何一行规则覆盖这个动作。 */
        public static final String PERMISSION_UNCOVERED   = "numen.permission.uncovered";
        /** 信号给主人看的自述，后接规则里的信号名({@code placed}……)。 */
        public static final String PERMISSION_SIGNAL_PREFIX = "numen.permission.signal.";

        // ==================== 征询（consent） ====================

        public static final String CONSENT_ALLOW          = "numen.consent.allow";
        public static final String CONSENT_ALLOW_REMEMBER = "numen.consent.allow_remember";
        public static final String CONSENT_DENY           = "numen.consent.deny";
        public static final String CONSENT_DENY_NOTE      = "numen.consent.deny_note";
        public static final String CONSENT_NOTE_ROW       = "numen.consent.note_row";
        /** "%s 请求你的同意,按 [%s] 答复"。 */
        public static final String CONSENT_ASKING         = "numen.consent.asking";
        /** "%s 的请求撤回了:%s"。 */
        public static final String CONSENT_WITHDRAWN      = "numen.consent.withdrawn";
        /** 答完以后消息下面那条结果:点了哪个键、拒绝时说的那句、在别处(命令)答的。 */
        public static final String CONSENT_CHOSE          = "numen.consent.chose";
        public static final String CONSENT_DENIED_SAYING  = "numen.consent.denied_saying";
        public static final String CONSENT_ANSWERED_ELSEWHERE = "numen.consent.answered_elsewhere";
        /** 清单一堆前面那个短动词,后接 {@code break}、{@code attack}、{@code command}……。 */
        public static final String CONSENT_VERB_PREFIX    = "numen.consent.verb.";
        /** 征询那条的问话:一件事是"想挖 [原木]×6,可以吗?"(%s 是动词加东西),几件事是一句总问。 */
        public static final String CONSENT_ASK            = "numen.consent.ask";
        public static final String CONSENT_ASK_MANY       = "numen.consent.ask_many";
        public static final String CONSENT_SECONDS        = "numen.consent.seconds";

        // ==================== 征询答复命令（/numen consent …） ====================

        public static final String COMMAND_CONSENT_ANSWERED     = "numen.command.consent.answered";
        public static final String COMMAND_CONSENT_NOT_OWNER    = "numen.command.consent.not_owner";
        public static final String COMMAND_CONSENT_NOT_PENDING  = "numen.command.consent.not_pending";

        // ==================== 权限命令（/numen permission …） ====================

        public static final String COMMAND_YOURS_NOT_HERE = "numen.command.permission.yours_not_here";
        public static final String COMMAND_MODE           = "numen.command.permission.mode";
        public static final String COMMAND_MODE_SET       = "numen.command.permission.mode_set";
        public static final String COMMAND_RULES_YOURS    = "numen.command.permission.rules_yours";
        public static final String COMMAND_RULES_FACTORY  = "numen.command.permission.rules_factory";
        public static final String COMMAND_RULES_NONE     = "numen.command.permission.rules_none";
        public static final String COMMAND_RULES_UNMATCHED = "numen.command.permission.rules_unmatched";
        public static final String COMMAND_RULE_ADDED     = "numen.command.permission.rule_added";
        public static final String COMMAND_RULE_EXISTS    = "numen.command.permission.rule_exists";
        public static final String COMMAND_RULE_NO_ROW    = "numen.command.permission.rule_no_row";
        public static final String COMMAND_RULE_REMOVED   = "numen.command.permission.rule_removed";
        public static final String COMMAND_RULES_RESET    = "numen.command.permission.rules_reset";
    }
}
