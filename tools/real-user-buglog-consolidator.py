import argparse, re, pathlib
SEV={"S1":100,"S2":50,"S3":20,"S4":5}
REP={"همیشه":1.5,"گاهی":1,"یک‌بار":0.7,"always":1.5,"sometimes":1,"once":0.7}
def clean(v): return re.sub(r"\s+"," ",v.replace("|","/").strip()) if v else "-"
def parse_block(block):
    mid=re.search(r"BUG-(\d+)",block)
    bid=f"BUG-{mid.group(1).zfill(3)}" if mid else "BUG-000"
    sev=(re.search(r"S[1-4]",block).group(0) if re.search(r"S[1-4]",block) else "S3")
    rep_m=re.search(r"همیشه|گاهی|یک‌بار|always|sometimes|once",block)
    rep=rep_m.group(0) if rep_m else "گاهی"
    mod_m=re.search(r"ماژول\s*[:→]\s*([^\n]+)",block)
    mod=clean(mod_m.group(1).split("→")[-1] if mod_m else "نامشخص")
    tit_m=re.search(r"عنوان کوتاه\s*[:：]\s*([^\n]+)",block)
    tit=clean(tit_m.group(1) if tit_m else "بدون عنوان")
    ph_m=re.search(r"فاز\s*(\d+)",block)
    phase=int(ph_m.group(1)) if ph_m else 99
    step_m=re.search(r"گام[‌ ]?های بازتولید\s*[:：]?\s*(.+?)(?:\nانتظار|\nواقعیت|\Z)",block,re.S)
    step=clean(step_m.group(1) if step_m else "-")
    exp_m=re.search(r"انتظار\s*[:：]\s*([^\n]+)",block)
    exp=clean(exp_m.group(1) if exp_m else "-")
    wit_m=re.search(r"شاهد\s*[:：]\s*([^\n]+)",block)
    wit=clean(wit_m.group(1) if wit_m else "-")
    score=SEV.get(sev,20)*REP.get(rep,1)
    return {"id":bid,"mod":mod,"sev":sev,"tit":tit,"phase":phase,"step":step,"exp":exp,"wit":wit,"score":score,"key":(mod,tit)}
def main():
    ap=argparse.ArgumentParser(description="Consolidate real-user buglog into scored TASKS rows")
    ap.add_argument("--buglog",default="docs/reports/real-user-scenario-buglog-2026-10-24.md")
    ap.add_argument("--tasks",default="docs/07_TASKS.md")
    args=ap.parse_args()
    text=pathlib.Path(args.buglog).read_text(encoding="utf-8",errors="ignore")
    text=re.sub(r"<!--.*?-->","",text,flags=re.S)
    parts=re.split(r"(?=^BUG-\d+)",text,flags=re.M)
    bugs=[parse_block(p) for p in parts if re.search(r"^BUG-\d+",p.strip(),re.M)]
    if not bugs:
        print("No bugs to consolidate — buglog is empty (awaiting device run)")
        tpath=pathlib.Path(args.tasks)
        ttext=tpath.read_text(encoding="utf-8",errors="ignore") if tpath.exists() else ""
        marker="<!-- real-user-buglog-consolidator: empty placeholder -->"
        if marker not in ttext:
            with open(args.tasks,"a",encoding="utf-8") as f:
                f.write(f"\n{marker}\n## Real-User Scenario — Consolidated Bugs (2026-10-24)\n")
                f.write("No bugs to consolidate — buglog is empty (awaiting device run).\n")
        return
    dedup={}
    for b in bugs:
        if b["key"] not in dedup or b["score"]>dedup[b["key"]]["score"]:
            dedup[b["key"]]=b
    scored=sorted(dedup.values(),key=lambda x:(-x["score"],x["phase"]))
    tpath=pathlib.Path(args.tasks)
    header="| شناسه | ماژول | شدت | عنوان | گام بازتولید | انتظار | شاهد | وضعیت |\n|---|---|---|---|---|---|---|---|\n"
    with open(args.tasks,"a",encoding="utf-8") as f:
        if header.strip() not in pathlib.Path(args.tasks).read_text(encoding="utf-8",errors="ignore"):
            f.write("\n"+header)
        for b in scored:
            f.write(f"| {b['id']} | {b['mod']} | {b['sev']} | {b['tit']} | {b['step']} | {b['exp']} | {b['wit']} | open |\n")
    print(f"Scored {len(bugs)} bugs, deduped to {len(scored)}, appended {len(scored)} rows to {args.tasks}")
if __name__=="__main__": main()
