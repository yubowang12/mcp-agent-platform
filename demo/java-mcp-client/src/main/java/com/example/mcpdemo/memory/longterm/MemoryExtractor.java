package com.example.mcpdemo.memory.longterm;

import com.example.mcpdemo.memory.config.MemoryProperties;
import com.example.mcpdemo.memory.model.MemoryCandidate;
import com.example.mcpdemo.memory.model.MemoryKind;
import com.example.mcpdemo.memory.model.SessionSummary;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 规则式记忆抽取（决策 3：**Host 规则写**，不由模型直接写）。
 *
 * <p>只抽两类（决策 6）：
 * <ul>
 *   <li><b>semantic</b>：用户明确要求记住（"记住…"），或陈述了偏好 / 事实
 *       （"我喜欢…""我叫…""我在准备…"）；</li>
 *   <li><b>episodic</b>：会话摘要（任务 + 工具 + 结论），只在真的干了活、且不是一次性会话时写。</li>
 * </ul>
 *
 * <p>**不写**（A5 的反例）：天气、时间、日期、算术、寒暄这类一次性信息——它们明天就过期，
 * 写进长期记忆只会污染召回。规则刻意保守：宁可少记，不可记错。
 *
 * <p>v2 再做"模型提议 + Host 审核"，本版保持纯规则、可单测。
 */
@Component
@ConditionalOnProperty(prefix = "mcp.memory", name = "enabled", havingValue = "true", matchIfMissing = true)
public class MemoryExtractor {

    /** 一次性信息：命中任意一条就不写语义记忆，也不写会话摘要。 */
    private static final List<String> TRANSIENT_MARKERS = List.of(
            "天气", "气温", "下雨", "温度", "几点了", "几点", "现在时间", "现在几点", "日期", "几号",
            "换算", "计算", "等于多少", "算一下", "现在是什么");

    /** 寒暄 / 客套：没有信息量。 */
    private static final List<String> GREETING_MARKERS = List.of(
            "你好", "您好", "在吗", "谢谢", "早上好", "晚上好", "再见", "拜拜", "辛苦了");

    /** 用户明确要求记住：最高优先级，即使是一次性会话也写。 */
    private static final List<String> EXPLICIT_MARKERS = List.of(
            "记住", "记一下", "别忘了", "以后都", "以后请", "从今往后", "请记得");

    /** 偏好表达。 */
    private static final List<String> PREFERENCE_MARKERS = List.of(
            "我喜欢", "我不喜欢", "我更喜欢", "我最喜欢", "我偏好", "我习惯", "我讨厌", "我倾向于");

    /** 事实 / 身份表达。 */
    private static final List<String> FACT_MARKERS = List.of(
            "我叫", "我的名字", "我是做", "我在准备", "我的目标是", "我负责", "我用的是");

    /** 疑问句里的"偏好词"不算陈述（"我叫什么名字？"不该记成事实）。 */
    private static final List<String> QUESTION_MARKERS = List.of(
            "什么", "怎么", "多少", "为什么", "哪一个", "吗", "？", "?");

    /** 值得记的会话：任务至少这么长，太短的多半是寒暄或一次性查询。 */
    private static final int MIN_EPISODIC_TASK_LENGTH = 12;
    /** 没调工具但想记摘要时，任务得更长一些（说明是真需求，不是随口一问）。 */
    private static final int MIN_EPISODIC_NO_TOOL_LENGTH = 16;
    /** 单条记忆的正文上限（防止把整段长文灌进记忆表）。 */
    private static final int MAX_MEMORY_LENGTH = 300;
    /** 会话摘要的固定置信度：是"摘要"而非用户原话，把握程度低于语义记忆。 */
    private static final double EPISODIC_CONFIDENCE = 0.6;

    /** 记忆层配置（默认置信度、episodic 保留天数、抽取开关）。 */
    private final MemoryProperties props;

    public MemoryExtractor(MemoryProperties props) {
        this.props = props;
    }

    /**
     * 从一次会话里抽取候选记忆。
     *
     * @return 可能是空列表（绝大多数普通问答不该留下长期记忆）
     */
    public List<MemoryCandidate> extract(SessionSummary session) {
        List<MemoryCandidate> candidates = new ArrayList<>();
        if (session == null || !props.extract().enabled()) {
            return candidates;
        }
        String task = normalize(session.task());
        if (task.isEmpty() || isTransient(task)) {
            return candidates;
        }

        MemoryCandidate semantic = extractSemantic(task);
        if (semantic != null) {
            candidates.add(semantic);
        }

        MemoryCandidate episodic = extractEpisodic(session, task);
        if (episodic != null) {
            candidates.add(episodic);
        }
        return candidates;
    }

    private MemoryCandidate extractSemantic(String task) {
        boolean explicit = containsAny(task, EXPLICIT_MARKERS);
        boolean question = isQuestion(task);
        boolean preference = containsAny(task, PREFERENCE_MARKERS);
        boolean fact = containsAny(task, FACT_MARKERS);

        if (!explicit && question) {
            return null;                       // 问句不是陈述，不记
        }
        if (!explicit && !preference && !fact) {
            return null;
        }
        double confidence = explicit
                ? Math.min(0.95, props.extract().defaultConfidence() + 0.15)
                : props.extract().defaultConfidence();
        return new MemoryCandidate(MemoryKind.SEMANTIC, task, confidence, null);
    }

    private MemoryCandidate extractEpisodic(SessionSummary session, String task) {
        if (!session.success() || session.oneOff()) {
            return null;                       // 失败会话不写；一次性会话没有跨会话价值
        }
        String answer = normalize(session.answer());
        if (answer.isEmpty() || task.length() < MIN_EPISODIC_TASK_LENGTH) {
            return null;
        }
        if (session.usedSteps() == 0 && task.length() < MIN_EPISODIC_NO_TOOL_LENGTH) {
            return null;
        }
        String tools = session.tools() == null || session.tools().isEmpty()
                ? "无"
                : String.join("/", session.tools());
        String text = "会话摘要｜任务：" + abbreviate(task, 80)
                + "｜工具：" + abbreviate(tools, 60)
                + "｜结果：" + abbreviate(answer, 120);
        return new MemoryCandidate(MemoryKind.EPISODIC, text, EPISODIC_CONFIDENCE,
                props.extract().episodicTtlDays());
    }

    private static boolean isTransient(String text) {
        return containsAny(text, TRANSIENT_MARKERS) || containsAny(text, GREETING_MARKERS);
    }

    private static boolean isQuestion(String text) {
        return containsAny(text, QUESTION_MARKERS);
    }

    private static boolean containsAny(String text, List<String> markers) {
        for (String marker : markers) {
            if (text.contains(marker)) {
                return true;
            }
        }
        return false;
    }

    private static String normalize(String text) {
        if (text == null) {
            return "";
        }
        String normalized = text.replaceAll("\\s+", " ").trim();
        return normalized.length() <= MAX_MEMORY_LENGTH
                ? normalized
                : normalized.substring(0, MAX_MEMORY_LENGTH);
    }

    private static String abbreviate(String text, int max) {
        return text.length() <= max ? text : text.substring(0, max) + "…";
    }
}
