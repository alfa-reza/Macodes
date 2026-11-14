# Macodes local workflow entry point (thin shell)
#
# All process knowledge lives in Gradle tasks (root build.gradle.kts); these are just quick aliases;
# calling ./gradlew <task> directly is equivalent, and CI reuses the same set of tasks.
#
# Variable injection: the project root .env (gitignored) is loaded automatically. Note the Gradle
# daemon does not pick up new environment variables; after changing secrets/.env run make gradle-stop.

-include .env
export

GRADLEW := ./gradlew

.DEFAULT_GOAL := help

.PHONY: help
help: ## Show this help
	@grep -hE '^[a-zA-Z_-]+:.*?## ' $(MAKEFILE_LIST) | \
		awk 'BEGIN {FS = ":.*?## "}; {printf "  \033[36m%-18s\033[0m %s\n", $$1, $$2}'

## ---------- Daily development (maps to gradle tasks) ----------

.PHONY: debug
debug: ## Build debug APK
	$(GRADLEW) :composeApp:assembleDebug

.PHONY: run
run: ## Build + install + launch (gradle runDebug)
	$(GRADLEW) runDebug

.PHONY: reinstall
reinstall: ## Uninstall then reinstall (gradle reinstallDebug, fixes signature conflicts)
	$(GRADLEW) reinstallDebug

## ---------- Tests ----------

.PHONY: test
test: ## Unit tests + integration tests (auto-SKIP when sshd/mosh are absent)
	$(GRADLEW) agentBridgeTest :composeApp:testDebugUnitTest

.PHONY: test-integration
test-integration: ## Integration tests: starts sshd automatically then runs (gradle testIntegration)
	$(GRADLEW) testIntegration

.PHONY: lint lint-kt
lint: ## Android lint
	$(GRADLEW) :composeApp:lintRelease

lint-kt: ## ktlint check (rules read from .editorconfig; ktlintFormat can auto-fix)
	$(GRADLEW) ktlintCheck

## ---------- Release ----------

.PHONY: release
release: ## Build signed release APK + AAB (validates signing secrets first)
	$(GRADLEW) checkSigningSecrets :composeApp:assembleRelease :composeApp:bundleRelease
	@echo "Artifacts: composeApp/build/outputs/{apk,bundle}/release/"

.PHONY: bump
bump: ## Bump the Android version, usage: make bump V=1.0.1 (preview: DRY=1)
	./scripts/bump-version.sh $(if $(DRY),--dry-run,) $(V)

## ---------- Maintenance ----------

.PHONY: gradle-stop
gradle-stop: ## Stop the Gradle daemon (required after changing environment variables)
	$(GRADLEW) --stop

.PHONY: clean
clean: ## Clean build artifacts
	$(GRADLEW) clean

.PHONY: devices
devices: ## List connected devices/emulators
	adb devices -l
