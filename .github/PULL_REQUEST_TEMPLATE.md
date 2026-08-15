## Summary

<!-- Briefly describe what this PR does -->

## Type

- [ ] feat: New feature
- [ ] fix: Bug fix
- [ ] refactor: Code refactoring
- [ ] test: Add/update tests
- [ ] docs: Documentation
- [ ] chore: Build/tooling

## Related Issue

<!-- Closes #123 -->

## Changes

<!-- List key changes -->

-

## Screenshots / Demo

<!-- If UI changes, attach screenshot or screen recording -->

## Checklist

- [ ] Commit messages follow [Conventional Commits](docs/engineering/git-workflow.md)（先写 message 再写代码，原子 commit）
- [ ] Code follows [style guide](docs/engineering/code-style.md)
- [ ] Every new `@Composable` Screen has a `@Preview`
- [ ] Tests added/updated and passing (`./gradlew testDebugUnitTest`)
- [ ] Local gate green before push: `./gradlew detekt` + `./gradlew ktfmtCheck`
- [ ] Documentation updated (docs-first, if applicable)
- [ ] No hardcoded strings, colors, or dimensions
- [ ] No `!!` or wildcard imports
