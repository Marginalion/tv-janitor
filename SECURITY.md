# Security

TV Janitor keeps network ADB on (that is how it works without a computer) and runs shell commands on the TV,
so security reports matter to us.

**Please don't open a public issue for a vulnerability.** Use **Security → Report a vulnerability** on this
repository; only the maintainer sees it.

What we already do, so you can check it: the app talks only to the TV's own ADB at 127.0.0.1; the bundled home
screen is checked by SHA-256 before install, on the copy the shell will install; Lite never goes online; nothing is
sent anywhere without the owner's explicit choice. If the firmware has `ro.adb.secure=0`, the app warns the owner.

---

**Уязвимость?** Не пишите в открытые issue: вкладка **Security → Report a vulnerability** видна только автору.
