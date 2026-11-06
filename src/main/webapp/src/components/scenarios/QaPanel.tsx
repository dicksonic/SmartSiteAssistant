import { useState } from "react";
import { api, type QaResult } from "@/lib/api";
import { Button } from "@/components/ui/button";
import { Textarea } from "@/components/ui/textarea";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { Checkbox } from "@/components/ui/checkbox";
import { Badge } from "@/components/ui/badge";
import { Volume2, Loader2, Send } from "lucide-react";
import { VoiceButton } from "@/components/VoiceButton";

/** 场景① 规范条文问答 */
export function QaPanel({ token }: { token: string }) {
  const [question, setQuestion] = useState("");
  const [result, setResult] = useState<QaResult | null>(null);
  const [checked, setChecked] = useState<Record<number, boolean>>({});
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [speaking, setSpeaking] = useState(false);

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
    try {
      const data = await api.ask(audioBase64 ? { token, audioBase64 } : { token, question });
      setResult(data);
      setChecked({});
    } catch (e) {
      setError(e instanceof Error ? e.message : "请求失败");
    } finally {
      setLoading(false);
    }
  };

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
              提问
            </Button>
            <VoiceButton disabled={loading} onAudio={submit} />
          </div>
          {error && <p className="text-sm text-destructive">{error}</p>}
        </CardContent>
      </Card>

      {result && (
        <Card>
          <CardHeader className="flex flex-row items-center justify-between space-y-0">
            <CardTitle className="text-base">AI 检查清单</CardTitle>
            <Button variant="outline" size="sm" onClick={() => speak(result.ttsText)} disabled={speaking}>
              <Volume2 className="mr-1 h-4 w-4" /> 语音播报
            </Button>
          </CardHeader>
          <CardContent className="space-y-4">
            <p className="text-sm text-muted-foreground">问：{result.question}</p>
            <p className="text-sm leading-6">{result.answer}</p>
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
        </Card>
      )}
    </div>
  );
}
