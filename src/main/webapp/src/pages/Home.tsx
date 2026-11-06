import { Link } from "react-router";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { Badge } from "@/components/ui/badge";
import { ShieldCheck, Building2, BarChart3, MessageSquareText, Camera, FileSearch, GitCompareArrows } from "lucide-react";

const scenes = [
  { icon: MessageSquareText, title: "规范条文问答", desc: "语音询问检查要点，AI即时返回检查清单" },
  { icon: Camera, title: "隐患智能描述", desc: "拍照/语音描述隐患，自动生成规范描述与整改要求" },
  { icon: FileSearch, title: "材料智能预审", desc: "自动识别材料完整性与逻辑一致性" },
  { icon: GitCompareArrows, title: "文书材料比对", desc: "整改前后描述与照片比对，辅助判断整改到位" },
];

const entries = [
  {
    to: "/platform",
    icon: Building2,
    title: "已建平台接入演示",
    desc: "模拟G端B/S监管平台：点击图标入口，弹层内嵌打开AI助手（方案2，免登无跳转）",
    tag: "平台接入",
  },
  {
    to: "/assistant",
    icon: ShieldCheck,
    title: "监管AI助手",
    desc: "四场景工作台：规范问答 / 隐患描述 / 材料预审 / 文书比对",
    tag: "核心应用",
  },
  {
    to: "/dashboard",
    icon: BarChart3,
    title: "预研数据看板",
    desc: "API调用量、用量、耗时、成功率——第四阶段预算修正的数据来源",
    tag: "数据汇聚",
  },
];

export default function Home() {
  return (
    <div className="min-h-screen bg-muted/30">
      <header className="border-b bg-background px-6 py-8">
        <div className="mx-auto max-w-4xl">
          <h1 className="text-2xl font-bold">监管AI助手 · 预研演示环境</h1>
          <p className="mt-2 text-sm text-muted-foreground">
            面向G端工程监管场景的产品智能AI预研：四大应用场景端到端跑通，模型调用全程计量，支撑API年费预算修正。
          </p>
        </div>
      </header>
      <main className="mx-auto max-w-4xl space-y-8 p-6">
        <section className="grid gap-4 md:grid-cols-3">
          {entries.map((e) => (
            <Link key={e.to} to={e.to}>
              <Card className="h-full transition-shadow hover:shadow-md">
                <CardHeader>
                  <div className="flex items-center justify-between">
                    <e.icon className="h-6 w-6 text-primary" />
                    <Badge variant="secondary">{e.tag}</Badge>
                  </div>
                  <CardTitle className="text-base">{e.title}</CardTitle>
                </CardHeader>
                <CardContent className="text-sm text-muted-foreground">{e.desc}</CardContent>
              </Card>
            </Link>
          ))}
        </section>
        <section>
          <h2 className="mb-3 text-base font-semibold">四大验证场景</h2>
          <div className="grid gap-3 md:grid-cols-2">
            {scenes.map((s) => (
              <div key={s.title} className="flex items-start gap-3 rounded-md border bg-background p-3">
                <s.icon className="mt-0.5 h-5 w-5 shrink-0 text-primary" />
                <div>
                  <p className="text-sm font-medium">{s.title}</p>
                  <p className="text-xs text-muted-foreground">{s.desc}</p>
                </div>
              </div>
            ))}
          </div>
        </section>
      </main>
    </div>
  );
}
