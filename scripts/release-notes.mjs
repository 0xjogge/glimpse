import { execFileSync } from "node:child_process";
import { mkdir, writeFile } from "node:fs/promises";
import {
  generateMarkDown,
  getGitDiff,
  loadChangelogConfig,
  parseCommits,
} from "changelogen";

const [tag, ref = tag, previous = ""] = process.argv.slice(2);
const pattern = /^(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)-release$/;
if (
  !pattern.test(tag || "") ||
  (previous && !pattern.test(previous)) ||
  (ref !== "HEAD" && ref !== tag)
) {
  throw new Error(
    "Usage: release-notes.mjs <feature>.<patch>-release [HEAD|tag] [previous-tag]",
  );
}
execFileSync("git", ["rev-parse", "--verify", `${ref}^{commit}`]);
const config = await loadChangelogConfig(process.cwd(), {
  repo: "0xjogge/glimpse",
  from: previous || ref,
  to: ref,
  newVersion: tag,
  templates: { tagBody: "{{newVersion}}" },
  noAuthors: true,
  hideAuthorEmail: true,
});
config.from = previous;
const commits = parseCommits(
  await getGitDiff(previous || undefined, ref),
  config,
);
let markdown = await generateMarkDown(
  commits.map((commit) => ({ ...commit, author: undefined })),
  config,
);
const contributors = new Set();
for (const commit of commits) {
  for (const author of commit.authors) {
    if (!author.name || author.name.includes("[bot]")) continue;
    contributors.add(`- ${author.name.replace(/[\[\]<>\r\n]/g, "")}`);
  }
}
if (contributors.size)
  markdown += `\n\n### Contributors\n\n${[...contributors].join("\n")}`;
await mkdir("build", { recursive: true });
await writeFile("build/release-notes.md", `${markdown}\n`);
console.log(markdown);
