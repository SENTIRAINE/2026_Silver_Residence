const { createApp } = Vue;

createApp({
    data() {
        return {
            activeCard: "login",
            isLoading: false,
            messageType: "",
            loginMessage: "",
            registerMessage: "",
            showLoginPassword: false,
            showRegisterPassword: false,
            loginForm: {
                username: "",
                password: ""
            },
            registerForm: {
                username: "",
                password: "",
                email: "",
                phone: ""
            }
        };
    },
    computed: {
        currentMessage() {
            return this.activeCard === "login" ? this.loginMessage : this.registerMessage;
        }
    },
    methods: {
        switchCard(card) {
            this.activeCard = card;
            this.clearMessages();
        },

        clearMessages() {
            this.messageType = "";
            this.loginMessage = "";
            this.registerMessage = "";
        },

        showPasswordHelp() {
            this.messageType = "success";
            this.loginMessage = "如忘记密码，请联系工作人员或由家属协助重置密码。";
        },

        validateLoginForm() {
            if (!this.loginForm.username || !this.loginForm.password) {
                this.messageType = "error";
                this.loginMessage = "请输入用户名和密码后再登录。";
                return false;
            }

            return true;
        },

        validateRegisterForm() {
            const { username, password, email, phone } = this.registerForm;

            if (!username || !password || !email || !phone) {
                this.messageType = "error";
                this.registerMessage = "请完整填写用户名、联系电话、邮箱和密码。";
                return false;
            }

            if (password.length < 6) {
                this.messageType = "error";
                this.registerMessage = "密码长度至少为 6 位，请重新输入。";
                return false;
            }

            if (!/^1\d{10}$/.test(phone)) {
                this.messageType = "error";
                this.registerMessage = "请输入正确的 11 位手机号。";
                return false;
            }

            return true;
        },

        async login() {
            this.clearMessages();

            if (!this.validateLoginForm()) {
                return;
            }

            this.isLoading = true;

            try {
                const response = await fetch("/user/login", {
                    method: "POST",
                    headers: {
                        "Content-Type": "application/json"
                    },
                    body: JSON.stringify(this.loginForm)
                });

                const data = await response.json();

                if (data.code === 1) {
                    localStorage.setItem("user", JSON.stringify(data.data));
                    this.messageType = "success";
                    this.loginMessage = "登录成功，正在进入用户页面。";
                    setTimeout(() => {
                        window.location.href = "/user.html";
                    }, 800);
                } else {
                    this.messageType = "error";
                    this.loginMessage = data.message || "登录失败，请检查账号和密码。";
                }
            } catch (error) {
                console.error("登录失败:", error);
                this.messageType = "error";
                this.loginMessage = "网络异常，请稍后重试或联系工作人员协助。";
            } finally {
                this.isLoading = false;
            }
        },

        async register() {
            this.clearMessages();

            if (!this.validateRegisterForm()) {
                return;
            }

            this.isLoading = true;

            try {
                const response = await fetch("/user/register", {
                    method: "POST",
                    headers: {
                        "Content-Type": "application/json"
                    },
                    body: JSON.stringify(this.registerForm)
                });

                const data = await response.json();

                if (data.code === 1) {
                    this.activeCard = "login";
                    this.messageType = "success";
                    this.loginMessage = "注册成功，请使用刚才填写的账号和密码登录。";
                    this.registerForm = {
                        username: "",
                        password: "",
                        email: "",
                        phone: ""
                    };
                } else {
                    this.messageType = "error";
                    this.registerMessage = data.message || "注册失败，请稍后再试。";
                }
            } catch (error) {
                console.error("注册失败:", error);
                this.messageType = "error";
                this.registerMessage = "网络异常，请稍后重试或联系工作人员协助。";
            } finally {
                this.isLoading = false;
            }
        }
    }
}).mount("#app");
