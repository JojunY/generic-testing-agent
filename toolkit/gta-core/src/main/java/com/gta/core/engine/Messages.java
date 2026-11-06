package com.gta.core.engine;

import java.util.Map;

/**
 * Everything gtaw says to people, in {@code output.language} (plan §5.10): {@code en} or {@code zh-CN}. Arguments are
 * {@link String#format} placeholders. Only prompts and the status block are translated; specs, briefs, agents and code
 * stay English.
 */
final class Messages {
    private static final Map<String, String> EN = Map.ofEntries(
            Map.entry("state.waiting", "waiting for you"),
            Map.entry("state.continuing", "continuing"),
            Map.entry("state.done", "done for now"),
            Map.entry("state.blocked", "stopped, needs you"),
            Map.entry("progress", "Progress: spec %s ▸ tests %d/%d passed ▸ defects found: %d"),
            Map.entry("spec.draft", "being written"),
            Map.entry("spec.in-review", "waiting for your answers"),
            Map.entry("spec.approved", "approved"),
            Map.entry("spec.stale", "to re-check"),
            Map.entry("spec.none", "not written yet"),
            Map.entry("section.happened", "What happened"),
            Map.entry("section.checked", "Checked"),
            Map.entry("section.needs", "Needs you"),
            Map.entry("needs.nothing", "nothing, continuing"),
            Map.entry("next", "Next: %s"),
            Map.entry("next.reply", "after your reply, gta continues"),
            Map.entry("next.report", "session ended, report %s; run /gta to continue"),
            Map.entry("next.continue", "gta continues"),
            Map.entry("next.pick", "gta writes the specs and asks you only what it cannot tell"),
            Map.entry("pick.title", "Pick a flow to work on:"),
            Map.entry("pick.item", "%d. %s (risk %d, spec: %s)"),
            Map.entry("pick.all", "All %d flows, riskiest first; a flow that waits for you does not stop the others"),
            Map.entry("pick.reply", "Reply OK to work on all flows, or a flow id to work on one."),
            Map.entry("parked.more", "Also waiting for you, asked after this one: %s"),
            Map.entry("ev.spec.written", "%s: spec %s written"),
            Map.entry("ev.answers", "%s: your answers to %s recorded"),
            Map.entry("ev.spec.approved", "%s: spec %s approved and committed"),
            Map.entry("ev.test.passed", "%s: test passed and committed"),
            Map.entry("ev.test.failed", "%s: test failed: %s"),
            Map.entry("ev.test.repair", "%s: %s, back to the test author"),
            Map.entry("ev.triage", "%s: the reviewer says %s: %s"),
            Map.entry("ev.defect.pinned", "%s: confirmed defect pinned with @KnownDefect"),
            Map.entry("ev.needs.human", "%s needs you: %s"),
            Map.entry("ev.session.ended", "session ended, quality gate: %s"),
            Map.entry("chk.reruns", "%s: gtaw re-ran it twice with the same result (not flaky)"),
            Map.entry("chk.flaky", "%s: the re-runs disagreed, so it is likely flaky"),
            Map.entry("chk.reviewer", "%s: the reviewer checked that the assertions match the spec"),
            Map.entry("chk.gate", "the quality gate checked every locked scenario against the base branch: %s"),
            Map.entry("verdict.product-defect", "a product defect"),
            Map.entry("verdict.test-defect", "a test defect"),
            Map.entry("verdict.flaky", "flaky"),
            Map.entry("verdict.spec-error", "a spec error"),
            Map.entry("verdict.spec-gap", "a gap in the spec"),
            Map.entry("verdict.expected-change", "an intended change"),
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
            Map.entry("current.code", "= current code"),
            Map.entry("question.title", "── %s: the test author asks ──"),
            Map.entry("question.recommended", "← recommended: %s"),
            Map.entry("question.reply", "Reply with your answer."),
            Map.entry("question.reply.ok", "Reply OK to take the recommendation, or with your own answer."),
            Map.entry("ev.question", "%s: the test author asked you a question"),
            Map.entry("ev.question.answered", "%s: your answer goes to the test author"));

    private static final Map<String, String> ZH_CN = Map.ofEntries(
            Map.entry("state.waiting", "等你处理"),
            Map.entry("state.continuing", "进行中"),
            Map.entry("state.done", "本次完成"),
            Map.entry("state.blocked", "已停下，需要你"),
            Map.entry("progress", "进度：规格%s ▸ 测试 %d/%d 通过 ▸ 发现 %d 个缺陷"),
            Map.entry("spec.draft", "编写中"),
            Map.entry("spec.in-review", "等你回答"),
            Map.entry("spec.approved", "已批准"),
            Map.entry("spec.stale", "待复查"),
            Map.entry("spec.none", "尚未编写"),
            Map.entry("section.happened", "刚才发生了什么"),
            Map.entry("section.checked", "已确认"),
            Map.entry("section.needs", "需要你"),
            Map.entry("needs.nothing", "无，继续中"),
            Map.entry("next", "下一步：%s"),
            Map.entry("next.reply", "你回复后，gta 继续"),
            Map.entry("next.report", "本次会话结束，报告：%s；执行 /gta 继续"),
            Map.entry("next.continue", "gta 继续"),
            Map.entry("next.pick", "gta 编写规格，只就看不出的地方问你"),
            Map.entry("pick.title", "选择要处理的 flow："),
            Map.entry("pick.item", "%d. %s（风险 %d，规格：%s）"),
            Map.entry("pick.all", "全部 %d 个 flow，风险高的先做；等你回复的 flow 不会挡住其他 flow"),
            Map.entry("pick.reply", "回复 OK 处理全部 flow，或回复一个 flow id 只处理它。"),
            Map.entry("parked.more", "这个之后还要问你的 flow：%s"),
            Map.entry("ev.spec.written", "%s：规格 %s 已写好"),
            Map.entry("ev.answers", "%s：已记录你对 %s 的回答"),
            Map.entry("ev.spec.approved", "%s：规格 %s 已批准并提交"),
            Map.entry("ev.test.passed", "%s：测试通过并已提交"),
            Map.entry("ev.test.failed", "%s：测试失败：%s"),
            Map.entry("ev.test.repair", "%s：%s，退回测试作者修改"),
            Map.entry("ev.triage", "%s：reviewer 判定为%s：%s"),
            Map.entry("ev.defect.pinned", "%s：已确认的缺陷已用 @KnownDefect 标注"),
            Map.entry("ev.needs.human", "%s 需要你：%s"),
            Map.entry("ev.session.ended", "会话结束，质量门禁：%s"),
            Map.entry("chk.reruns", "%s：gtaw 重跑 2 次，结果一致（不是不稳定）"),
            Map.entry("chk.flaky", "%s：重跑结果不一致，可能不稳定"),
            Map.entry("chk.reviewer", "%s：reviewer 已确认断言与规格一致"),
            Map.entry("chk.gate", "质量门禁已按目标分支检查所有锁定的场景：%s"),
            Map.entry("verdict.product-defect", "产品缺陷"),
            Map.entry("verdict.test-defect", "测试缺陷"),
            Map.entry("verdict.flaky", "不稳定"),
            Map.entry("verdict.spec-error", "规格有误"),
            Map.entry("verdict.spec-gap", "规格缺漏"),
            Map.entry("verdict.expected-change", "有意的改动"),
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
            Map.entry("current.code", "= 代码现状"),
            Map.entry("question.title", "── %s：测试作者提问 ──"),
            Map.entry("question.recommended", "← 推荐：%s"),
            Map.entry("question.reply", "请回复你的答案。"),
            Map.entry("question.reply.ok", "回复 OK 接受推荐，或直接写你的答案。"),
            Map.entry("ev.question", "%s：测试作者向你提了一个问题"),
            Map.entry("ev.question.answered", "%s：你的回答已转给测试作者"));

    private final Map<String, String> table;

    private Messages(Map<String, String> table) {
        this.table = table;
    }

    static Messages of(String language) {
        return new Messages("zh-CN".equals(language) ? ZH_CN : EN);
    }

    boolean has(String key) {
        return table.containsKey(key);
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
