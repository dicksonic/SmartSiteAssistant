package com.reg.assistant.hazard;

import java.nio.file.Path;
import java.util.List;

/**
 * 隐患图片取数抽象。本期 LocalDirHazardImageFetcher（本地目录模拟）；
 * 失效条件：接入业务系统附件中心后，新增 ApiHazardImageFetcher（按附件 id 拉流），
 * HazardRecognizeService 主链不变——这是把"取数"从"识别"里拆出来的全部理由。
 */
public interface HazardImageFetcher {

    /** 按引用取图，返回本地可读路径；取不到/引用非法抛 ApiException */
    Path fetch(String imageRef);

    /** 当前可选图片清单（本地模拟期给前端挑选器用；接附件中心后由业务列表页取代） */
    List<ImageItem> list();

    record ImageItem(String name, long sizeKb) {}
}