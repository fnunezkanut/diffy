// Pure logic for the Bitbucket snapshot: query building, mapping, classification.
//
// No chrome.* and no fetch in this file, so it can be loaded by importScripts in
// the service worker and by Node alike. Comment text is deliberately never
// requested, mapped or stored.
(function (root) {
  const PR_PAGE_SIZE = 20;
  const PR_MAX_PAGES = 5;
  const NOTIFICATION_LOOKBACK_DAYS = 7;
  const NOTIFICATION_PAGE_SIZE = 50;
  const NOTIFICATION_MAX_PAGES = 40;
  const NOTIFICATION_CATEGORIES = ["direct"];

  // Generic words, so new bots are caught without code changes. Whole word match only.
  const BOT_NAME_KEYWORDS = ["PR", "Ranger", "Buddy", "Agent", "Bot"];

  // Prefixes that look like a Jira project but never are.
  const NON_JIRA_PREFIXES = ["UTF", "SHA", "ISO", "HTTP", "TLS", "AES"];

  const CATEGORY = {
    BOT_REVIEW_COMMENT: "bot_review_comment",
    HUMAN_COMMENT: "human_comment",
    APPROVAL: "approval",
    REVIEWER_ADDED: "reviewer_added",
    MENTION: "mention",
    PR_UPDATED: "pr_updated",
    MERGED: "merged",
    OTHER_BITBUCKET: "other_bitbucket",
  };

  const PR_FIELDS = [
    "-values.closed_by",
    "-values.description",
    "-values.summary",
    "-values.rendered",
    "-values.properties",
    "-values.reason",
    "-values.reviewers",
    "+values.destination.branch.name",
    "+values.destination.repository.full_name",
    "+values.destination.repository.name",
    "+values.source.branch.name",
    "+values.source.commit.hash",
  ].join(",");

  const WORKSPACE_PATTERN = /^[A-Za-z0-9._~-]+$/;
  const UUID_PATTERN = /^[0-9a-f-]{36}$/i;

  function stripBraces(uuid) {
    return String(uuid || "").replace(/[{}]/g, "");
  }

  function isValidWorkspace(slug) {
    return typeof slug === "string" && WORKSPACE_PATTERN.test(slug);
  }

  // The uuid goes into a query string, so it is validated to stop query injection.
  function buildPullRequestQuery(userUuid) {
    const uuid = stripBraces(userUuid);
    if (!UUID_PATTERN.test(uuid)) {
      throw new Error("Invalid user uuid");
    }
    return `state="OPEN" AND queued=false AND reviewers.uuid="${uuid}"`;
  }

  function buildPullRequestUrl({ workspace, userUuid, page }) {
    if (!isValidWorkspace(workspace)) {
      throw new Error("Invalid workspace");
    }
    const q = buildPullRequestQuery(userUuid);
    return (
      `/!api/internal/workspaces/${workspace}/pullrequests/` +
      `?fields=${encodeURIComponent(PR_FIELDS)}` +
      `&page=${Number(page) || 1}&pagelen=${PR_PAGE_SIZE}` +
      `&q=${encodeURIComponent(q)}`
    );
  }

  function isBotMatch(displayName) {
    if (typeof displayName !== "string" || displayName.trim() === "") {
      return null;
    }
    const tokens = displayName.split(/[^\p{L}\p{N}]+/u).filter(Boolean);
    for (const token of tokens) {
      const lower = token.toLowerCase();
      const keyword = BOT_NAME_KEYWORDS.find((word) => word.toLowerCase() === lower);
      if (keyword) {
        return keyword;
      }
    }
    return null;
  }

  function isBotName(displayName) {
    return isBotMatch(displayName) !== null;
  }

  // Finds every distinct Jira key. For branches, "PROJ-101-102" also yields PROJ-102.
  function extractJiraKeys(text, { expandNumbers = false } = {}) {
    if (typeof text !== "string") {
      return [];
    }
    const keys = [];
    const add = (key) => {
      const prefix = key.slice(0, key.indexOf("-"));
      if (!NON_JIRA_PREFIXES.includes(prefix) && !keys.includes(key)) {
        keys.push(key);
      }
    };
    if (expandNumbers) {
      const pattern = /\b([A-Z][A-Z0-9]+)-(\d+)((?:-\d+)*)/g;
      for (const match of text.matchAll(pattern)) {
        add(`${match[1]}-${match[2]}`);
        for (const extra of match[3].split("-").filter(Boolean)) {
          add(`${match[1]}-${extra}`);
        }
      }
    } else {
      for (const match of text.matchAll(/\b[A-Z][A-Z0-9]+-\d+\b/g)) {
        add(match[0]);
      }
    }
    return keys;
  }

  function extractTitleTags(title) {
    const lead = /^(\s*\[[^\]]+\])+/.exec(title || "");
    if (!lead) {
      return [];
    }
    return Array.from(lead[0].matchAll(/\[[^\]]+\]/g), (match) => match[0]);
  }

  function mapParticipant(participant) {
    const user = participant.user || {};
    return {
      displayName: user.display_name || "",
      accountId: user.account_id || null,
      uuid: user.uuid ? stripBraces(user.uuid) : null,
      state: participant.state || null,
      approved: Boolean(participant.approved),
      participatedOn: participant.participated_on || null,
    };
  }

  function mapPullRequest(raw, workspace, userUuid) {
    const repository = (raw.destination && raw.destination.repository) || {};
    const fullName = repository.full_name || "";
    const title = raw.title || "";
    const sourceBranch = (raw.source && raw.source.branch && raw.source.branch.name) || null;
    const author = raw.author || {};

    let jiraKeys = extractJiraKeys(title);
    let jiraKeySource = jiraKeys.length > 0 ? "title" : null;
    if (jiraKeys.length === 0 && sourceBranch) {
      jiraKeys = extractJiraKeys(sourceBranch, { expandNumbers: true });
      jiraKeySource = jiraKeys.length > 0 ? "branch" : null;
    }

    const participants = raw.participants || [];
    const reviewers = participants.filter((p) => p.role === "REVIEWER").map(mapParticipant);
    const others = participants.filter((p) => p.role !== "REVIEWER").map(mapParticipant);
    const me = stripBraces(userUuid);
    const mine = reviewers.find((reviewer) => reviewer.uuid === me);

    return {
      id: raw.id,
      workspace,
      repoSlug: repository.name || "",
      repoFullName: fullName,
      title,
      titleTags: extractTitleTags(title),
      jiraKeys,
      jiraKeySource,
      url: `https://bitbucket.org/${fullName}/pull-requests/${raw.id}`,
      state: raw.state,
      draft: Boolean(raw.draft),
      author: {
        displayName: author.display_name || "",
        accountId: author.account_id || null,
        uuid: author.uuid ? stripBraces(author.uuid) : null,
      },
      sourceBranch,
      destinationBranch: (raw.destination && raw.destination.branch && raw.destination.branch.name) || null,
      sourceCommit: (raw.source && raw.source.commit && raw.source.commit.hash) || null,
      createdOn: raw.created_on || null,
      updatedOn: raw.updated_on || null,
      commentCount: raw.comment_count || 0,
      taskCount: raw.task_count || 0,
      approvalCount: reviewers.filter((r) => r.state === "approved").length,
      changesRequestedCount: reviewers.filter((r) => r.state === "changes_requested").length,
      myReviewState: mine ? mine.state : null,
      reviewers,
      otherParticipants: others,
    };
  }

  // Comment text is not selected on purpose (no bodyItems).
  const NOTIFICATION_SELECTION =
    "notificationId timestamp readState category " +
    "analyticsAttributes { key value } " +
    "content { type message url " +
    "actor { displayName ari actorType } " +
    "entity { title url } " +
    "path { title url } }";

  function buildNotificationQuery() {
    return (
      "query DiffyNotificationFeed($first: Int, $after: String, $filter: InfluentsNotificationFilter, $ari: String) { " +
      "notifications { notificationFeed(first: $first, after: $after, filter: $filter, collabContextRoutingAri: $ari) { " +
      `nodes { groupId groupSize headNotification { ${NOTIFICATION_SELECTION} } } ` +
      "pageInfo { endCursor hasNextPage } } } }"
    );
  }

  function buildNotificationGroupQuery() {
    return (
      "query DiffyNotificationGroup($groupId: String!, $first: Int, $after: String, $filter: InfluentsNotificationFilter, $ari: String) { " +
      "notifications { notificationGroup(groupId: $groupId, first: $first, after: $after, filter: $filter, collabContextRoutingAri: $ari) { " +
      `nodes { ${NOTIFICATION_SELECTION} } ` +
      "pageInfo { endCursor hasNextPage } } } }"
    );
  }

  function getTemplateId(notification) {
    const attributes = (notification && notification.analyticsAttributes) || [];
    const found = attributes.find((attribute) => attribute.key === "messageTemplateId");
    return found ? found.value : null;
  }

  function parseGroupId(groupId) {
    const match = /^([0-9a-f-]{36}):(\d+)$/i.exec(groupId || "");
    if (!match) {
      return null;
    }
    return { repoUuid: match[1], prId: Number(match[2]) };
  }

  function isBitbucketUrl(url) {
    try {
      return new URL(url).hostname === "bitbucket.org";
    } catch (error) {
      return false;
    }
  }

  function actorOf(notification) {
    return (notification.content && notification.content.actor) || {};
  }

  function isBotActor(actor) {
    const hinted = actor.actorType && actor.actorType !== "USER";
    return isBotMatch(actor.displayName) !== null || Boolean(hinted);
  }

  // Returns a CATEGORY value, or null when the notification is not from Bitbucket.
  function classifyNotification(notification) {
    const content = notification.content || {};
    const templateId = getTemplateId(notification);
    const onBitbucket = isBitbucketUrl(content.url);

    if (onBitbucket && /mentioned you/i.test(content.message || "")) {
      return CATEGORY.MENTION;
    }
    if (templateId === "bitbucket-pull-request-reviewer-added") {
      return CATEGORY.REVIEWER_ADDED;
    }
    if (templateId === "bitbucket-pull-request-approved") {
      return CATEGORY.APPROVAL;
    }
    if (templateId === "bitbucket-pull-request-comment") {
      return isBotActor(actorOf(notification)) ? CATEGORY.BOT_REVIEW_COMMENT : CATEGORY.HUMAN_COMMENT;
    }
    if ((templateId && templateId.startsWith("bitbucket-")) || onBitbucket) {
      return CATEGORY.OTHER_BITBUCKET;
    }
    return null;
  }

  function cleanUrl(url) {
    try {
      const parsed = new URL(url);
      parsed.searchParams.delete("link_source");
      parsed.searchParams.delete("xpis");
      return parsed.toString();
    } catch (error) {
      return null;
    }
  }

  function mapNotificationEvent(notification, group) {
    const content = notification.content || {};
    const actor = actorOf(notification);
    const ids = parseGroupId(group && group.groupId);
    const url = cleanUrl(content.url);
    const accountMatch = /user\/(.+)$/.exec(actor.ari || "");
    const comment = /#comment-(\d+)/.exec(url || "");
    const prUrl = /^(https:\/\/bitbucket\.org\/[^/]+\/[^/]+\/pull-requests\/\d+)/.exec(url || "");
    const botMatchedOn = isBotMatch(actor.displayName);
    const entityTitle = (content.entity && content.entity.title) || "";

    return {
      id: notification.notificationId,
      occurredAt: notification.timestamp,
      category: classifyNotification(notification),
      templateId: getTemplateId(notification),
      readState: notification.readState || null,
      message: content.message || null,
      actor: {
        displayName: actor.displayName || "",
        accountId: accountMatch ? accountMatch[1] : null,
        isBot: isBotActor(actor),
        botMatchedOn,
      },
      pullRequest: {
        id: ids ? ids.prId : null,
        repoSlug: (content.path && content.path[0] && content.path[0].title) || null,
        repoUuid: ids ? ids.repoUuid : null,
        title: entityTitle.replace(/^#\d+:\s*/, ""),
        url: prUrl ? prUrl[1] : null,
      },
      commentId: comment ? Number(comment[1]) : null,
      onDiff: url ? new URL(url).pathname.includes("/diff") : false,
      url,
      groupId: (group && group.groupId) || null,
      groupSize: (group && group.groupSize) || 1,
    };
  }

  function dedupeEvents(events) {
    const seen = new Set();
    const unique = [];
    for (const event of events) {
      if (!seen.has(event.id)) {
        seen.add(event.id);
        unique.push(event);
      }
    }
    return unique.sort((a, b) => Date.parse(b.occurredAt) - Date.parse(a.occurredAt));
  }

  function buildSnapshot({ meta, pullRequests, notificationEvents }) {
    return { schemaVersion: 1, meta, pullRequests, notificationEvents };
  }

  const api = {
    PR_PAGE_SIZE,
    PR_MAX_PAGES,
    NOTIFICATION_LOOKBACK_DAYS,
    NOTIFICATION_PAGE_SIZE,
    NOTIFICATION_MAX_PAGES,
    NOTIFICATION_CATEGORIES,
    BOT_NAME_KEYWORDS,
    NON_JIRA_PREFIXES,
    CATEGORY,
    PR_FIELDS,
    isValidWorkspace,
    stripBraces,
    buildPullRequestQuery,
    buildPullRequestUrl,
    isBotName,
    extractJiraKeys,
    mapPullRequest,
    buildNotificationQuery,
    buildNotificationGroupQuery,
    getTemplateId,
    parseGroupId,
    classifyNotification,
    mapNotificationEvent,
    dedupeEvents,
    buildSnapshot,
  };

  if (typeof module !== "undefined" && module.exports) {
    module.exports = api;
  } else {
    root.DiffySnapshot = api;
  }
})(typeof globalThis !== "undefined" ? globalThis : this);
