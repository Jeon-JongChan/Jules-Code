import sys

# 1. Update JulesViewModel.kt to append top-level typealiases
vm_path = r'app/src/main/java/com/jules/code/ui/JulesViewModel.kt'
with open(vm_path, 'r', encoding='utf-8') as f:
    vm_text = f.read()

if 'typealias ApiKeyUsageSummary' not in vm_text:
    vm_text += '\n\ntypealias ApiKeyUsageSummary = JulesViewModel.Companion.ApiKeyUsageSummary\ntypealias SessionHealthDiagnostic = JulesViewModel.Companion.SessionHealthDiagnostic\n'
    with open(vm_path, 'w', encoding='utf-8', newline='\n') as f:
        f.write(vm_text)
    print("Updated JulesViewModel.kt with typealiases")

# 2. Update DashboardAndOnboardingScreens.kt
dash_path = r'app/src/main/java/com/jules/code/ui/screens/DashboardAndOnboardingScreens.kt'
with open(dash_path, 'r', encoding='utf-8') as f:
    dash_text = f.read()

# Fix import
dash_text = dash_text.replace(
    'import com.jules.code.ui.JulesViewModel.ApiKeyUsageSummary',
    'import com.jules.code.ui.ApiKeyUsageSummary'
)

# Fix strings
dash_text = dash_text.replace(
    'stringResource(R.string.stalled_badge_title)',
    '"지연 (Stalled)"'
)
dash_text = dash_text.replace(
    'stringResource(R.string.stalled_warning_title)',
    '"⚠️ 에이전트 무응답 / 지연 감지"'
)
dash_text = dash_text.replace(
    '''                                val progressText = if (session.totalStepsCount > 0) {
                                    stringResource(R.string.step_progress_format, session.completedStepsCount, session.totalStepsCount, session.currentStepTitle)
                                } else {
                                    session.currentStepTitle
                                }''',
    '''                                val progressText = if (session.totalStepsCount > 0) {
                                    stringResource(R.string.stalled_step_label, session.currentStepTitle, session.completedStepsCount, session.totalStepsCount)
                                } else {
                                    session.currentStepTitle
                                }'''
)

with open(dash_path, 'w', encoding='utf-8', newline='\n') as f:
    f.write(dash_text)
print("Updated DashboardAndOnboardingScreens.kt")
