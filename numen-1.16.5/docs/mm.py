"""Query Mojang official mappings (1.16.5 client.txt) for class members.

File format (proguard-style, but REVERSED from the usual convention):
    <deobfuscated.Class.Name> -> <obf>:
        <line>:<line>:<returnType> <name>(<args>) -> <obfMember>

Usage:
    python mm.py <substring-of-class-name> [regex-filter-for-members]
"""
import re
import sys

PATH = "client.txt"   # 官方 1.16.5 映射，获取方式见 PORTING.md 第六节
CLASS_RE = re.compile(r"^(\S+) -> (\S+):\s*$")


def load(path=PATH):
    """Return {deobfClassName: {'obf': str, 'members': [signature, ...]}}."""
    classes = {}
    cur = None
    with open(path, encoding="utf-8") as fh:
        for line in fh:
            if line.startswith("#") or not line.strip():
                continue
            m = CLASS_RE.match(line.rstrip("\n"))
            if m:
                cur = m.group(1)          # left side = readable name
                classes[cur] = {"obf": m.group(2), "members": []}
                continue
            if cur and line.startswith("    "):
                mem = line.strip()
                # strip the trailing " -> obf" so what prints is the named signature
                mem = re.sub(r"\s*->\s*\S+$", "", mem)
                classes[cur]["members"].append(mem)
    return classes


def main():
    classes = load()
    if len(sys.argv) < 2:
        print("classes:", len(classes))
        return
    needle = sys.argv[1]
    pat = re.compile(sys.argv[2], re.I) if len(sys.argv) > 2 else None
    names = sorted(k for k in classes if needle in k)
    if not names:
        print("no class matching", needle)
        return
    for name in names:
        info = classes[name]
        print(f"### {name}   (obf {info['obf']})")
        for mem in info["members"]:
            if pat is None or pat.search(mem):
                print("   ", mem)
        print()


if __name__ == "__main__":
    main()
