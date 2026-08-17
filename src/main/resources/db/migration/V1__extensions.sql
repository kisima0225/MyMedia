-- pg_trgm 提供三元组索引，是本项目中文搜索的主路径。
-- PostgreSQL 内置的 to_tsvector 不切分中文，无法满足需求，详见 spec 7.7。
CREATE EXTENSION IF NOT EXISTS pg_trgm;
