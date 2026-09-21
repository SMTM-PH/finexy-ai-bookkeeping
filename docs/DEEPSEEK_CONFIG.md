# DeepSeek 文本记账配置

项目复用 Finexy 的 OpenAI 兼容接口。DeepSeek 将自然语言转换为结构化交易；字段完整时自动正式入账，字段缺失时打开编辑窗口等待确认。

在 `conf/ezbookkeeping.ini` 中设置：

```ini
[llm]
transaction_from_ai_text_recognition = true

[llm_text_recognition]
llm_provider = openai_compatible
enable_thinking = off
openai_compatible_base_url = https://api.deepseek.com
openai_compatible_api_key = YOUR_DEEPSEEK_API_KEY
openai_compatible_model_id = deepseek-v4-flash
request_timeout = 60000
proxy = none
skip_tls_verify = false
```

也可以登录 Web 工作台，在“应用设置 → AI 配置”中维护相同参数。只有服务器最早创建且仍有效的账户可以修改这项全局配置；保存后模型会立即切换，无需重启。页面不会回显或写入浏览器存储 API Key，Web 覆盖配置保存在服务端持久化数据目录的 `data/ai-configuration.json`，并优先于上述环境变量。

如果不使用页面配置，修改配置文件或容器环境变量后仍需重启服务。

`deepseek-chat` 是文本模型，不直接承担图片识别。购物截图和支付截图将先发送给局域网内的 OCR 服务，原图识别成功后删除，只把 OCR 文本交给上述文本识别接口。
