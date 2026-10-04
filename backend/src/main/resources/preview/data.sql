-- Synthetic, disposable fixtures only; never copied from a real database.
INSERT INTO categories(id,name,sort_order,created_at,updated_at) VALUES(1,'童年动画',1,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP);
INSERT INTO items(id,type,title,description,content,theme,status,like_count,created_at,updated_at) VALUES
(1,'text','预览测试：童年记忆','用于测试搜索和主题筛选的合成内容','这不是生产内容。','OLD','VISIBLE',5,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP),
(2,'text','预览测试：开心时刻','用于测试另一主题','这不是生产内容。','SMILE','VISIBLE',2,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP),
(3,'text','预览测试：候选内容','用于测试候选栏目','这不是生产内容。','OLD','CANDIDATE',0,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP);
INSERT INTO item_tags(item_id,category_id) VALUES(1,1),(3,1);
