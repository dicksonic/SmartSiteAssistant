import { useRef, useState } from "react";
import { api, type HazardResult } from "@/lib/api";
import { Button } from "@/components/ui/button";
import { Textarea } from "@/components/ui/textarea";
import { Input } from "@/components/ui/input";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { Badge } from "@/components/ui/badge";
import { Camera, Loader2, X } from "lucide-react";
import { VoiceButton } from "@/components/VoiceButton";

/** 场景② 隐患智能描述：拍照/语音/文字 → 规范描述与整改要求 */
export function HazardPanel({ token }: { token: string }) {
  const [image, setImage] = useState<{ base64: string; preview: string } | null>(null);
  const [text, setText] = useState("");
  const [location, setLocation] = useState("");
  const [result, setResult] = useState<HazardResult | null>(null);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const fileRef = useRef<HTMLInputElement>(null);

  const pickImage = (file: File) => {
    const reader = new FileReader();
    reader.onload = () => {
      const dataUrl = reader.result as string;
      setImage({ base64: dataUrl.split(",")[1], preview: dataUrl });
    };
    reader.readAsDataURL(file);
  };

  const submit = async (audioBase64?: string) => {
    setLoading(true);
    setError(null);
    setResult(null);
    try {
      setResult(await api.describeHazard({
        token,
        imageBase64: image?.base64,
        text: text || undefined,
        audioBase64,
        location: location || undefined,
      }));
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
          <CardTitle className="text-base">隐患采集（拍照 / 语音 / 文字）</CardTitle>
        </CardHeader>
        <CardContent className="space-y-3">
          <div className="flex items-center gap-3">
            <Button variant="outline" onClick={() => fileRef.current?.click()}>
              <Camera className="mr-2 h-4 w-4" /> 拍摄/上传照片
            </Button>
            <input
              ref={fileRef}
              type="file"
              accept="image/*"
              capture="environment"
              className="hidden"
              onChange={(e) => e.target.files?.[0] && pickImage(e.target.files[0])}
            />
            <Input placeholder="隐患部位（如：3号楼5层临边）" value={location} onChange={(e) => setLocation(e.target.value)} className="max-w-xs" />
          </div>
          {image && (
            <div className="relative w-40">
              <img src={image.preview} alt="隐患照片" className="w-40 rounded-md border" />
              <Button variant="destructive" size="icon" className="absolute -right-2 -top-2 h-6 w-6" onClick={() => setImage(null)}>
                <X className="h-3 w-3" />
              </Button>
            </div>
          )}
          <Textarea placeholder="补充口述/文字描述（可选）" value={text} onChange={(e) => setText(e.target.value)} rows={2} />
          <div className="flex gap-2">
            <Button onClick={() => submit()} disabled={loading || (!image && !text.trim())}>
              {loading && <Loader2 className="mr-2 h-4 w-4 animate-spin" />}
              生成规范描述
            </Button>
            <VoiceButton disabled={loading} onAudio={submit} />
          </div>
          {error && <p className="text-sm text-destructive">{error}</p>}
        </CardContent>
      </Card>

      {result && (
        <Card>
          <CardHeader className="flex flex-row items-center justify-between space-y-0">
            <CardTitle className="text-base">隐患记录（自动生成）</CardTitle>
            <Badge>{result.category}</Badge>
          </CardHeader>
          <CardContent className="space-y-3 text-sm">
            <div>
              <p className="font-medium text-muted-foreground">隐患部位</p>
              <p>{result.location}</p>
            </div>
            <div>
              <p className="font-medium text-muted-foreground">规范描述</p>
              <p className="leading-6">{result.description}</p>
            </div>
            <div>
              <p className="font-medium text-muted-foreground">整改要求</p>
              <p className="leading-6">{result.rectification}</p>
            </div>
            <div>
              <p className="font-medium text-muted-foreground">依据</p>
              <Badge variant="secondary">{result.basis}</Badge>
            </div>
          </CardContent>
        </Card>
      )}
    </div>
  );
}
