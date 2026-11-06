package com.gta.core.engine;

import java.util.Map;

/**
 * Everything gtaw says to people, in {@code output.language} (plan §5.10): {@code en} or {@code zh-CN}. Arguments are
 * {@link String#format} placeholders. Only prompts and the status block are translated; specs, briefs, agents and code
 * stay English.
 */
final class Messages {
    private static final Map<String, String> EN = Map.ofEntries(
            Map.entry("questions.title", "── %s · %s · %s for you ──"),
            Map.entry("questions.count.one", "1 question"),
            Map.entry("questions.count.many", "%d questions"),
            Map.entry("spec", "Spec: %s"),
            Map.entry("high", "[high impact] "),
            Map.entry("recommended", "  ← recommended"),
            Map.entry("recommended.why", "  ← recommended: %s"),
            Map.entry("findings", "Findings:"),
            Map.entry(
                    "questions.reply",
                    "Reply OK to take every recommended answer; to choose another one, add the question number and"
                            + " letter (\"OK, 2B\"); to answer in your own words, write \"2: <your answer>\" on its own"
                            + " line. \"rest default\" takes \"= current code\" for the remaining normal-impact"
                            + " questions."),
            Map.entry("approval.title", "── %s@%s ready for approval ──"),
            Map.entry("approval.summary", "Spec: %s, %d requirements, %d scenarios"),
            Map.entry("approval.confirmed", "  · %d confirmed by you or by a document"),
            Map.entry(
                    "approval.locked", "  · %d read from the code, %d taken as is: their tests lock today's behaviour"),
            Map.entry("approval.reply", "Reply OK to approve %s@%s, or say what to change."),
            Map.entry("defect.title", "── %s looks like a product defect ──"),
            Map.entry("human.title", "── %s needs you ──"),
            Map.entry("last.failure", "Last failure (%s): %s"),
            Map.entry("escalation.file", "Details: %s"),
            Map.entry(
                    "defect.a",
                    "A) Yes, a defect: pin it with @KnownDefect (the build stays green) and draft an issue"),
            Map.entry("defect.b", "B) No, the spec is wrong: back to review"),
            Map.entry("defect.c", "C) Run it again"),
            Map.entry("defect.why", "the reviewer judged it against an expectation a person or a document confirmed"),
            Map.entry("escalation.a", "A) Run it again (once you fixed the cause)"),
            Map.entry("escalation.b", "B) Cancel this item"),
            Map.entry("escalation.why", "nothing is lost; fix the cause described in the details first"),
            Map.entry("decision.reply", "Reply OK to take the recommendation, or the letter of another option."),
            Map.entry("naming.title", "── %s: the test class name is taken ──"),
            Map.entry(
                    "naming.body",
                    "gta names this test %s (one test class per source class and kind),\n"
                            + "but that file already exists and is not a gta test (last change by %s)."),
            Map.entry("naming.a", "A) Let gta add its tests to that file (tagged gta; the diff shows every change)"),
            Map.entry("naming.b", "B) Skip this test for now (the item is cancelled)"),
            Map.entry("naming.c", "C) I renamed or moved my file: check again"),
            Map.entry("naming.why", "gta only adds tests tagged gta and never changes yours"),
            Map.entry("current.code", "= current code"));

    private static final Map<String, String> ZH_CN = Map.ofEntries(
            Map.entry("questions.title", "── %s · %s · 需要你回答 %s ──"),
            Map.entry("questions.count.one", "1 个问题"),
            Map.entry("questions.count.many", "%d 个问题"),
            Map.entry("spec", "规格：%s"),
            Map.entry("high", "[高影响] "),
            Map.entry("recommended", "  ← 推荐"),
            Map.entry("recommended.why", "  ← 推荐：%s"),
            Map.entry("findings", "发现："),
            Map.entry(
                    "questions.reply",
                    "回复 OK 接受全部推荐答案；要改选某题，写题号加字母（例如 \"OK, 2B\"）；要用自己的话回答，单独一行写"
                            + " \"2: <你的答案>\"。\"其余默认\" 会让其余普通影响的题目选 \"= 代码现状\"。"),
            Map.entry("approval.title", "── %s@%s 待批准 ──"),
            Map.entry("approval.summary", "规格：%s，%d 个需求，%d 个场景"),
            Map.entry("approval.confirmed", "  · %d 个由你或文档确认"),
            Map.entry("approval.locked", "  · %d 个从代码读出、%d 个按现状接受：它们的测试锁定现在的行为"),
            Map.entry("approval.reply", "回复 OK 批准 %s@%s；要修改的话，直接说要改什么。"),
            Map.entry("defect.title", "── %s 疑似产品缺陷 ──"),
            Map.entry("human.title", "── %s 需要你处理 ──"),
            Map.entry("last.failure", "最近一次失败（%s）：%s"),
            Map.entry("escalation.file", "详情：%s"),
            Map.entry("defect.a", "A) 是缺陷：用 @KnownDefect 标记（构建保持绿灯），并起草一个 issue"),
            Map.entry("defect.b", "B) 不是，规格写错了：退回重新审查"),
            Map.entry("defect.c", "C) 再运行一次"),
            Map.entry("defect.why", "reviewer 依据由人或文档确认过的期望判定"),
            Map.entry("escalation.a", "A) 再运行一次（先修正原因）"),
            Map.entry("escalation.b", "B) 取消这个条目"),
            Map.entry("escalation.why", "不会丢失任何东西；请先按详情修正原因"),
            Map.entry("decision.reply", "回复 OK 接受推荐，或回复其他选项的字母。"),
            Map.entry("naming.title", "── %s：测试类名已被占用 ──"),
            Map.entry("naming.body", "gta 把这个测试命名为 %s（每个源类、每种测试只有一个测试类），\n" + "但这个文件已经存在，而且不是 gta 的测试（最后修改者：%s）。"),
            Map.entry("naming.a", "A) 让 gta 把测试加到这个文件里（带 gta tag；diff 会显示每一处改动）"),
            Map.entry("naming.b", "B) 先跳过这个测试（取消该条目）"),
            Map.entry("naming.c", "C) 我已经改名或移走了我的文件：重新检查"),
            Map.entry("naming.why", "gta 只添加带 gta tag 的测试，不会改你的测试"),
            Map.entry("current.code", "= 代码现状"));

    private final Map<String, String> table;

    private Messages(Map<String, String> table) {
        this.table = table;
    }

    static Messages of(String language) {
        return new Messages("zh-CN".equals(language) ? ZH_CN : EN);
    }

    String t(String key, Object... args) {
        String pattern = table.get(key);
        if (pattern == null) {
            throw new IllegalArgumentException("no message " + key);
        }
        return args.length == 0 ? pattern : String.format(pattern, args);
    }

    /** Both catalogues name the same keys: a missing translation would fail at run time, so tests check this. */
    static boolean complete() {
        return EN.keySet().equals(ZH_CN.keySet());
    }
}
