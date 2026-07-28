-- v2: llm_api_keys.model 单列 → available_models JSON 列表(一个 key 多模型)
-- 设计 .evo/tasks/ai-chat-model-select-v2/tech-design.md §2.1
--
-- 先加列 + backfill 现有 model 行,再删旧列(防现有 key 静默丢失 model 设置)。
-- available_models 允许 NULL:OPENAI/ANTHROPIC 不配走 adapter 默认;COMPATIBLE 必填由 Service 层校验。
ALTER TABLE llm_api_keys ADD COLUMN available_models TEXT;
UPDATE llm_api_keys
   SET available_models = json_build_array(model)::text
 WHERE model IS NOT NULL AND model <> '';
ALTER TABLE llm_api_keys DROP COLUMN model;
