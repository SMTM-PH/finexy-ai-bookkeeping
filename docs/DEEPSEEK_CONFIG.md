# DeepSeek 文本记账配置

项目复用 ezBookkeeping 的 OpenAI 兼容接口。DeepSeek 将自然语言转换为结构化交易；字段完整时自动正式入账，字段缺失时打开编辑窗口等待确认。

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

修改后需要重启服务。API Key 只能通过 NAS 上的配置文件或容器 Secret 注入，不应写入前端、Git 仓库或浏览器存储。

`deepseek-chat` 是文本模型，不直接承担图片识别。购物截图和支付截图将先发送给局域网内的 OCR 服务，原图识别成功后删除，只把 OCR 文本交给上述文本识别接口。
