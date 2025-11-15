# 黑马点评——AI版
**1.新增了ai助手的板块(通过`langchain4j`实现），在用户发博客，浏览店铺时可以给予帮助（紫色按钮）**

**2.优化了前端界面，增加了丰富的交互反馈。同时也修复了原版的一些小bug，现在上传图片可以超过1mb啦**

<img width="291" height="566" alt="image" src="https://github.com/user-attachments/assets/0ce6108a-2d59-411a-bc13-4e7b985d776e" />
<img width="289" height="566" alt="image" src="https://github.com/user-attachments/assets/02aae766-c787-4817-b74c-36cf2a37210c" />
<img width="291" height="573" alt="image" src="https://github.com/user-attachments/assets/be5254a9-77e0-48a6-a543-960f5b64eefa" />




## 使用教程：


**1.项目通过`maven`构建，`springboot`版本为3.5.6，`jdk17`。**

**2.使用时需将`backend/src/main/resources/application.yml`中的数据库和redis配置修改成自己的，还有`api-key`也需要修改（可以在系统环境变量里添加`API-KEY`)**

**3.还需要将`backend/src/main/java/com/hmdp/utils/SystemConstants.java`中的`IMAGE_UPLOAD_DIR`路径修改成对应文件夹下的路径，防止出现无法上传博客的现象**

**4.后端程序启动之前，需要先在redis中执行`XGROUP CREATE stream.orders g1 0 MKSTREAM`**

**5.后端程序启动之前，需要先执行`backend/src/test/java/com/hmdp/HmDianPingApplicationTests.java`**
