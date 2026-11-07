import { useState } from "react";
import { api, type QaResult } from "@/lib/api";
import { Button } from "@/components/ui/button";
import { Textarea } from "@/components/ui/textarea";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { Checkbox } from "@/components/ui/checkbox";
import { Badge } from "@/components/ui/badge";
import { Volume2, Loader2, Send, Bot, AlertTriangle } from "lucide-react";
import { VoiceButton } from "@/components/VoiceButton";



/** 场景① 规范条文问答 */
export function QaPanel({ token }: { token: string }) {
  const [question, setQuestion] = useState("");
  const [result, setResult] = useState<QaResult | null>(null);
  const [checked, setChecked] = useState<Record<number, boolean>>({});
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [speaking, setSpeaking] = useState(false);
  const [compare, setCompare] = useState(false);
  const [agentResult, setAgentResult] = useState<QaAgentResult | null>(null);
  const [agentError, setAgentError] = useState<string | null>(null);

  const speak = async (text: string) => {
    setSpeaking(true);
    try {
      const res = await api.synthesize({ token, text });
      if (res.audioBase64) {
        new Audio(`data:audio/mp3;base64,${res.audioBase64}`).play();
      } else {
        // mock模式：降级为浏览器语音播报
        const utter = new SpeechSynthesisUtterance(text);
        utter.lang = "zh-CN";
        speechSynthesis.speak(utter);
      }
    } finally {
      setSpeaking(false);
    }
  };

  const submit = async (audioBase64?: string) => {
    setLoading(true);
    setError(null);
    setResult(null);
    setAgentResult(null);
    setAgentError(null);
    try {
      if (compare && !audioBase64) {
            const [normal, agent] = await Promise.allSettled([
              api.ask({ token, question }),
              api.askAgent({ token, question }),
            ]);
            if (normal.status === "fulfilled") { setResult(normal.value); setChecked({}); }
            else setError(normal.reason instanceof Error ? normal.reason.message : "普通模式请求失败");
            if (agent.status === "fulfilled") setAgentResult(agent.value);
            else setAgentError(agent.reason instanceof Error ? agent.reason.message : "Agent 模式请求失败");
          } else if (compare && audioBase64) {
            // 语音+对比：先 ASR，再把识别文本喂给 agent，保证同题
            const normal = await api.ask({ token, audioBase64 });
            setResult(normal);
            setChecked({});
            try {
              setAgentResult(await api.askAgent({ token, question: normal.question }));
            } catch (e) {
              setAgentError(e instanceof Error ? e.message : "Agent 模式请求失败");
            }
          } else {
            // ★ 原来的那行挪进这个 else（不开对比时的原路径，一字不改）
            const data = await api.ask(audioBase64 ? { token, audioBase64 } : { token, question });
            setResult(data);
            setChecked({});
          }
    } catch (e) {
      setError(e instanceof Error ? e.message : "请求失败");
    } finally {
      setLoading(false);
    }
  };
  /** 普通模式结果内容（单跑/对比两种布局复用） */
  const normalBody = result && (
    <CardContent className="space-y-4">
      <p className="text-sm text-muted-foreground">问：{result.question}</p>
      <p className="text-sm leading-6 whitespace-pre-wrap">{result.answer}</p>
      <div className="space-y-2">
        {result.checklist.map((item, i) => (
          <label key={i} className="flex items-start gap-2 rounded-md border p-2 text-sm">
            <Checkbox
              checked={!!checked[i]}
              onCheckedChange={(v) => setChecked((s) => ({ ...s, [i]: !!v }))}
              className="mt-0.5"
            />
            <span className={checked[i] ? "text-muted-foreground line-through" : ""}>{item}</span>
          </label>
        ))}
      </div>
      <div className="flex flex-wrap gap-1">
        {result.references.map((r, i) => (
          <Badge key={i} variant="secondary">{r}</Badge>
        ))}
      </div>
    </CardContent>
  );

  const agentUsage = agentResult?.usage ?? agentResult?.agentUsage;

  /** Agent 模式结果内容（附耗时/真实 token 诊断） */
  const agentBody = (
    <CardContent className="space-y-4">
      {agentError && <p className="text-sm text-destructive">{agentError}</p>}
      {agentResult && (
        <>
          <div className="flex flex-wrap gap-1">
            <Badge variant="outline">耗时 {(agentResult.agentLatencyMs / 1000).toFixed(1)}s</Badge>
            {agentUsage && (
              <Badge variant="outline">
                tokens {agentUsage.totalTokens}（入 {agentUsage.promptTokens} / 出 {agentUsage.completionTokens}）
              </Badge>
            )}
            {agentResult.degraded && (
              <Badge variant="destructive">
                <AlertTriangle className="mr-1 h-3 w-3" /> 已降级普通模式
              </Badge>
            )}
          </div>
          {agentResult.degraded && agentResult.degradeReason && (
            <p className="text-xs text-muted-foreground">降级原因：{agentResult.degradeReason}</p>
          )}
          <p className="text-sm leading-6 whitespace-pre-wrap">{agentResult.answer}</p>
        </>
      )}
    </CardContent>
  );
  return (
    <div className="space-y-4">
      <Card>
        <CardHeader>
          <CardTitle className="text-base">现场提问</CardTitle>
        </CardHeader>
        <CardContent className="space-y-3">
          <Textarea
            placeholder="例如：深基坑支护关键部位的检查要点有哪些？（或点击麦克风语音提问）"
            value={question}
            onChange={(e) => setQuestion(e.target.value)}
            rows={3}
          />
          <div className="flex gap-2">
            <Button onClick={() => submit()} disabled={loading || !question.trim()}>
              {loading ? <Loader2 className="mr-2 h-4 w-4 animate-spin" /> : <Send className="mr-2 h-4 w-4" />}
              {loading ? (compare ? "双路推理中，Agent 可能要 30 秒…" : "提问中…") : "提问"}
            </Button>
            <label className="ml-auto flex items-center gap-2 text-sm text-muted-foreground">
              <Checkbox checked={compare} onCheckedChange={(v) => setCompare(!!v)} disabled={loading} />
              对比模式（普通 vs Agent）
            </label>
            <VoiceButton disabled={loading} onAudio={submit} />
          </div>
          {error && <p className="text-sm text-destructive">{error}</p>}
        </CardContent>
      </Card>

      {!compare && result && (
        <Card>
          <CardHeader className="flex flex-row items-center justify-between space-y-0">
            <CardTitle className="text-base">AI 检查清单</CardTitle>
            <Button variant="outline" size="sm" onClick={() => speak(result.ttsText)} disabled={speaking}>
              <Volume2 className="mr-1 h-4 w-4" /> 语音播报
            </Button>
          </CardHeader>
          {normalBody}
        </Card>
      )}

      {compare && (result || agentResult || agentError) && (
        <div className="grid gap-4 md:grid-cols-2">
          <Card>
            <CardHeader className="flex flex-row items-center justify-between space-y-0">
              <CardTitle className="text-base">普通模式（单发问答）</CardTitle>
              {result && (
                <Button variant="outline" size="sm" onClick={() => speak(result.ttsText)} disabled={speaking}>
                  <Volume2 className="mr-1 h-4 w-4" /> 语音播报
                </Button>
              )}
            </CardHeader>
            {result ? normalBody : (
              <CardContent><p className="text-sm text-muted-foreground">（无结果）</p></CardContent>
            )}
          </Card>
          <Card>
            <CardHeader className="flex flex-row items-center justify-between space-y-0">
              <CardTitle className="flex items-center gap-2 text-base">
                <Bot className="h-4 w-4" /> Agent 模式（G-AIOS）
              </CardTitle>
            </CardHeader>
            {agentBody}
          </Card>
        </div>
      )}
    </div>
  );
}
