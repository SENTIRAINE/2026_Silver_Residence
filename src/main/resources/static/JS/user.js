const GEOSCENE_PORTAL_URL = "https://edutrial.geoscene.cn/geoscene";

function runWhenDomReady(callback) {
    if (document.readyState === "loading") {
        window.addEventListener("DOMContentLoaded", callback);
        return;
    }

    callback();
}

function removeMapOverlays() {
    document
        .querySelectorAll("#mapContainer .map-shade, #mapContainer .grid-pattern")
        .forEach((element) => element.remove());
}

function initUserMap() {
    if (typeof window.require !== "function") {
        console.error("GeoScene SDK 未加载，window.require 不存在");
        return;
    }

    window.require([
        "geoscene/config",
        "geoscene/Map",
        "geoscene/views/MapView"
    ], (geosceneConfig, GeoSceneMap, MapView) => {
        const mapContainer = document.getElementById("mapContainer");

        if (!mapContainer) {
            console.error("地图容器 #mapContainer 不存在");
            return;
        }

        geosceneConfig.portalUrl = GEOSCENE_PORTAL_URL;

        const baseMap = new GeoSceneMap({
            basemap: "tianditu-vector"
        });

        const view = new MapView({
            container: mapContainer,
            map: baseMap,
            zoom: 10,
            center: [121.62, 38.91]
        });

        window.userMapDebug = {
            baseMap,
            mapContainer,
            view
        };

        mapContainer.classList.add("is-loaded");
        removeMapOverlays();

        view
            .when(() => {
                console.info("GeoScene 底图初始化完成", view);
            })
            .catch((error) => {
                console.error("GeoScene 底图初始化失败:", error);
            });
    });
}

function initUserApp() {
    if (!window.Vue) {
        console.error("Vue 未加载，用户页面交互无法初始化");
        return;
    }

    const { createApp } = window.Vue;

    createApp({
        data() {
            return {
                searchKeyword: "",
                assistantCollapsed: true,
                chatInput: "",
                chatMessages: [],
                currentUser: null
            };
        },
        mounted() {
            const savedUser = localStorage.getItem("user");

            if (!savedUser) {
                return;
            }

            try {
                this.currentUser = JSON.parse(savedUser);
            } catch (error) {
                console.error("解析登录用户失败:", error);
            }
        },
        methods: {
            formatTime() {
                return new Date().toLocaleTimeString("zh-CN", {
                    hour: "2-digit",
                    minute: "2-digit"
                });
            },

            toggleAssistant() {
                this.assistantCollapsed = !this.assistantCollapsed;
            },

            goToProfile() {
                if (!this.currentUser) {
                    window.location.href = "/index.html";
                    return;
                }

                this.pushAssistantReply("个人中心入口已保留，后续可接入用户资料、收藏和退出登录页面。");
            },

            async handleSearch() {
                const keyword = this.searchKeyword.trim();

                if (!keyword) {
                    this.pushAssistantReply("请输入筛选需求，例如“200 万以内、靠近医院、电梯房”。");
                    return;
                }

                await this.submitRequirement(keyword, "search");
            },

            async sendMessage() {
                const message = this.chatInput.trim();

                if (!message) {
                    return;
                }

                this.chatInput = "";
                await this.submitRequirement(message, "chat");
            },

            async submitRequirement(message, source) {
                this.chatMessages.push({
                    id: `user-${Date.now()}`,
                    role: "user",
                    content: message,
                    time: this.formatTime()
                });

                window.dispatchEvent(new CustomEvent("agent-map-filter-request", {
                    detail: {
                        source,
                        text: message
                    }
                }));

                try {
                    const response = await fetch("/ai/chat", {
                        method: "POST",
                        headers: {
                            "Content-Type": "text/plain;charset=UTF-8"
                        },
                        body: message
                    });

                    if (!response.ok || !response.body) {
                        throw new Error("AI 接口响应异常");
                    }

                    const reader = response.body.getReader();
                    const decoder = new TextDecoder("utf-8");
                    let fullText = "";

                    while (true) {
                        const { value, done } = await reader.read();

                        if (done) {
                            break;
                        }

                        fullText += decoder.decode(value, { stream: true });
                    }

                    const content = fullText
                        .split("\n")
                        .filter((line) => line.startsWith("data: "))
                        .map((line) => line.replace("data: ", "").trim())
                        .join("");

                    this.pushAssistantReply(
                        content || "已收到您的需求。后续接入 agent 后，可把自然语言转成地图筛选条件并返回点要素。"
                    );
                } catch (error) {
                    console.error("AI 对话失败:", error);
                    this.pushAssistantReply("助手暂时无法连接，请稍后重试。");
                }
            },

            pushAssistantReply(content) {
                this.chatMessages.push({
                    id: `assistant-${Date.now()}-${Math.random().toString(16).slice(2, 8)}`,
                    role: "assistant",
                    content,
                    time: this.formatTime()
                });
            }
        }
    }).mount("#user-app");
}

runWhenDomReady(() => {
    initUserApp();
    initUserMap();
});
