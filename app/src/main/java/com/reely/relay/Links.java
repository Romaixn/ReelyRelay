package com.reely.relay;

/**
 * Every URL that depends on where this project is hosted, in one place - a
 * fork or a repository rename only has to touch GITHUB_OWNER/GITHUB_REPO.
 *
 * SITE is the GitHub Pages site served from this repository's /docs folder
 * (Settings -> Pages -> Deploy from a branch -> main, /docs). The QR codes
 * the app shows all point there, so Pages has to be on for them to work.
 */
final class Links {

    private static final String GITHUB_OWNER = "Romaixn";
    private static final String GITHUB_REPO = "ReelyRelay";

    /** GitHub's "latest release" endpoint - see GithubReleaseClient. */
    static final String LATEST_RELEASE_API =
            "https://api.github.com/repos/" + GITHUB_OWNER + "/" + GITHUB_REPO + "/releases/latest";

    // GitHub Pages lowercases nothing but the host: the path keeps the
    // repository's exact casing.
    private static final String SITE = "https://" + GITHUB_OWNER.toLowerCase(java.util.Locale.ROOT)
            + ".github.io/" + GITHUB_REPO + "/";

    /** The "Accessibility won't turn on" walkthrough - see RestrictedSettingsStepFragment. */
    static final String ACCESSIBILITY_HELP = SITE + "#accessibility";

    /** The phone-side page of the "send from your phone" flow - see PhonePairing. */
    static final String PAIRING_PAGE = SITE + "pair.html";

    /** Where to get Reely Player itself. */
    static final String REELY_PLAYER_SITE = "reelyplayer.app";

    private Links() {
    }
}
