path = 'app/build.gradle.kts'
with open(path, 'r', encoding='utf-8') as f:
    text = f.read()

text = text.replace('versionCode = 2', 'versionCode = 3')
text = text.replace('versionName = "1.1.0"', 'versionName = "1.2.0"')

old_block = '''    applicationVariants.all {
        outputs.all {
            (this as? com.android.build.gradle.internal.api.BaseVariantOutputImpl)?.outputFileName =
                if (buildType.name == "release") "Jules Code.apk" else "Jules Code-${buildType.name}.apk"
        }
    }'''

new_block = '''    applicationVariants.all {
        val vName = versionName ?: "1.2.0"
        outputs.all {
            (this as? com.android.build.gradle.internal.api.BaseVariantOutputImpl)?.outputFileName =
                if (buildType.name == "release") "Jules Code-v${vName}.apk" else "Jules Code-v${vName}-${buildType.name}.apk"
        }
    }'''

text = text.replace(old_block, new_block)

with open(path, 'w', encoding='utf-8', newline='') as f:
    f.write(text)

print("Updated app/build.gradle.kts successfully")
