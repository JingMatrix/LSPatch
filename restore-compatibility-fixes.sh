#!/bin/bash

# LSPatch libxposed API 100 兼容性修复自动恢复脚本

set -e

echo "🔧 LSPatch libxposed API 100 兼容性修复恢复脚本"
echo "============================================="

# 检查是否在LSPatch根目录
if [ ! -f "settings.gradle.kts" ]; then
    echo "❌ 错误: 请在LSPatch根目录下运行此脚本"
    exit 1
fi

echo "📍 当前目录: $(pwd)"

# 选择恢复方法
echo ""
echo "请选择恢复方法:"
echo "1. 使用Git标签恢复 (推荐)"
echo "2. 应用补丁文件"
echo "3. 重新设置libxposed环境"
echo ""
read -p "请输入选择 (1-3): " choice

case $choice in
    1)
        echo "🔄 使用Git标签恢复..."
        if git tag | grep -q "v0.7-libxposed100-compatible"; then
            git checkout v0.7-libxposed100-compatible
            echo "✅ 成功切换到兼容版本"
        else
            echo "❌ 未找到标签，请使用方法2或3"
            exit 1
        fi
        ;;
    2)
        echo "🔄 应用补丁文件..."
        if [ -f "libxposed-api100-compatibility-fixes.patch" ]; then
            git apply libxposed-api100-compatibility-fixes.patch
            echo "✅ 补丁应用成功"
        else
            echo "❌ 未找到补丁文件"
            exit 1
        fi
        ;;
    3)
        echo "🔄 重新设置libxposed环境..."
        echo "⚠️  这将花费较长时间，请确保网络连接正常"
        read -p "是否继续? (y/N): " confirm
        if [[ $confirm =~ ^[Yy]$ ]]; then
            # 设置代理（根据需要修改）
            export https_proxy=http://127.0.0.1:7890 
            export http_proxy=http://127.0.0.1:7890 
            export all_proxy=socks5://127.0.0.1:7890
            
            mkdir -p libxposed && cd libxposed
            
            echo "📥 克隆libxposed api..."
            git clone --depth 1 https://github.com/libxposed/api.git
            
            echo "📥 克隆libxposed service..."
            git clone --depth 1 https://github.com/libxposed/service.git
            
            echo "📦 发布api到本地maven..."
            cd api && ./gradlew :api:publishApiPublicationToMavenLocal
            
            echo "📦 发布service到本地maven..."
            cd ../service && ./gradlew :interface:publishInterfacePublicationToMavenLocal
            
            cd ../..
            echo "✅ libxposed环境设置完成"
        else
            echo "❌ 用户取消操作"
            exit 1
        fi
        ;;
    *)
        echo "❌ 无效选择"
        exit 1
        ;;
esac

echo ""
echo "🧪 验证编译环境..."
echo "正在清理构建缓存..."
./gradlew clean > /dev/null 2>&1

echo "正在编译Debug版本..."
if ./gradlew buildDebug > build.log 2>&1; then
    echo "✅ 编译成功！"
    echo ""
    echo "📦 编译产物:"
    ls -lh out/debug/*.apk out/debug/*.jar 2>/dev/null || echo "   (未找到编译产物，请检查out/debug目录)"
else
    echo "❌ 编译失败，请检查build.log文件"
    echo "最后几行错误信息:"
    tail -10 build.log
    exit 1
fi

echo ""
echo "🎉 恢复完成！现在可以正常使用LSPatch了。"
echo ""
echo "📚 更多信息请查看 COMPATIBILITY_FIXES_README.md" 