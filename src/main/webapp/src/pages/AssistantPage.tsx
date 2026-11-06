import { useEffect, useState } from "react";
import { useSearchParams } from "react-router";
import { api, type SessionInfo } from "@/lib/api";
import { Tabs, TabsContent, TabsList, TabsTrigger } from "@/components/ui/tabs";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { Input } from "@/components/ui/input";
import { Button } from "@/components/ui/button";
import { Badge } from "@/components/ui/badge";
import { ShieldCheck } from "lucide-react";
import { QaPanel } from "@/components/scenarios/QaPanel";
import { HazardPanel } from "@/components/scenarios/HazardPanel";
import { MaterialPanel } from "@/components/scenarios/MaterialPanel";
import { ComparePanel } from "@/components/scenarios/ComparePanel";

/** AI助手主界面：四场景工作台。通过 ?token= 免登进入（平台弹层iframe或小程序WebView） */
export default function AssistantPage() {
  const [params, setParams] = useSearchParams();
  const [token, setToken] = useState(params.get("token") ?? "");
  const embed = params.get("embed") === "1";
  const [demoName, setDemoName] = useState("张工");
  const [session, setSession] = useState<SessionInfo | null>(null);
  const [checking, setChecking] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [entering, setEntering] = useState(false);

  useEffect(() => {
    document.title = "监管AI助手";
  }, []);

  useEffect(() => {
    if (!token) return;
    setChecking(true);
    api
      .sessionInfo(token)
      .then(setSession)
      .catch((e) => setError(e instanceof Error ? e.message : "会话校验失败"))
      .finally(() => setChecking(false));
  }, [token]);

  const enterDemo = async () => {
    setEntering(true);
    try {
      const d = await api.demoSign({
        appKey: "demo-platform",
        userName: demoName,
        orgName: "市建设工程质量安全监督站",
      });
      setToken(d.token);
      params.set("token", d.token);
      setParams(params, { replace: true });
    } catch (e) {
      setError(e instanceof Error ? e.message : "进入失败");
    } finally {
      setEntering(false);
    }
  };

  // 无Token：演示环境直接输入姓名进入（生产环境必须从平台入口携带Token）
  if (!token) {
    return (
      <div className="flex min-h-screen items-center justify-center bg-muted/40 p-4">
        <Card className="w-full max-w-sm">
          <CardHeader>
            <CardTitle className="flex items-center gap-2 text-base">
              <ShieldCheck className="h-5 w-5 text-primary" /> 监管AI助手 · 演示进入
            </CardTitle>
          </CardHeader>
          <CardContent className="space-y-3">
            <p className="text-sm text-muted-foreground">
              生产环境由已建平台图标入口免登进入；此处为演示入口（模拟平台侧签名换Token）。
            </p>
            <Input value={demoName} onChange={(e) => setDemoName(e.target.value)} placeholder="检查人员姓名" />
            <Button className="w-full" disabled={entering || !demoName.trim()} onClick={enterDemo}>
              进入助手
            </Button>
            {error && <p className="text-sm text-destructive">{error}</p>}
          </CardContent>
        </Card>
      </div>
    );
  }

  if (checking) {
    return <div className="flex min-h-screen items-center justify-center text-sm text-muted-foreground">正在校验会话…</div>;
  }
  if (error || !session) {
    return (
      <div className="flex min-h-screen items-center justify-center p-4 text-center text-sm text-destructive">
        {error ?? "会话校验失败"}
      </div>
    );
  }

  return (
    <div className="min-h-screen bg-muted/30">
      {!embed && (
        <header className="border-b bg-background px-4 py-3">
          <div className="mx-auto flex max-w-3xl items-center justify-between">
            <h1 className="flex items-center gap-2 text-lg font-semibold">
              <ShieldCheck className="h-5 w-5 text-primary" /> 监管AI助手
            </h1>
            <div className="flex items-center gap-2">
              <Badge variant="secondary">{session.platformName}</Badge>
              <Badge variant="outline">{session.userName}</Badge>
            </div>
          </div>
        </header>
      )}
      <main className="mx-auto max-w-3xl p-4">
        <Tabs defaultValue="qa">
          <TabsList className="grid w-full grid-cols-4">
            <TabsTrigger value="qa">规范问答</TabsTrigger>
            <TabsTrigger value="hazard">隐患描述</TabsTrigger>
            <TabsTrigger value="material">材料预审</TabsTrigger>
            <TabsTrigger value="compare">文书比对</TabsTrigger>
          </TabsList>
          <TabsContent value="qa" className="mt-4"><QaPanel token={token} /></TabsContent>
          <TabsContent value="hazard" className="mt-4"><HazardPanel token={token} /></TabsContent>
          <TabsContent value="material" className="mt-4"><MaterialPanel token={token} /></TabsContent>
          <TabsContent value="compare" className="mt-4"><ComparePanel token={token} /></TabsContent>
        </Tabs>
      </main>
    </div>
  );
}
