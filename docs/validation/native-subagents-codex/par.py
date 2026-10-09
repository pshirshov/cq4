import json,glob,sys,hashlib
H='/tmp/cxq/home/sessions'
pid=sys.argv[1]
def sha(s): return hashlib.sha256(s.encode()).hexdigest()[:12]
files={}
for f in glob.glob(H+'/**/*.jsonl',recursive=True):
    ls=[json.loads(l) for l in open(f)]; files[ls[0]['payload']['id']]=(f,ls)
pf,pl=[v for k,v in files.items() if k.startswith(pid)][0]
print('PARENT',pf.split('rollout-')[1])
for e in pl:
    p=e.get('payload',{}); t=p.get('type')
    if t in('function_call','custom_tool_call'): print(e['timestamp'],'CALL',p.get('name'),(str(p.get('arguments') or p.get('input')))[:60].replace('\n',' '))
    elif t in('function_call_output','custom_tool_call_output'): print(e['timestamp'],'OUT ',str(p.get('output'))[:160].replace('\n',' '))
    elif t=='agent_message': print(e['timestamp'],'AGENT_MSG',p['author'],'->',p['recipient'],json.dumps(p['content'])[:200])
    elif t=='task_complete': print(e['timestamp'],'PARENT task_complete last=',repr(p.get('last_agent_message'))[:200], 'sha',sha(p.get('last_agent_message') or ''))
for k,(f,ls) in files.items():
    s=ls[0]['payload'].get('source')
    if isinstance(s,dict) and s['subagent']['thread_spawn']['parent_thread_id'].startswith(pid):
        name=s['subagent']['thread_spawn']['agent_path']
        tcs=[e for e in ls if e.get('payload',{}).get('type')=='task_complete']
        st=[e for e in ls if e.get('payload',{}).get('type')=='task_started']
        lm=tcs[-1]['payload'].get('last_agent_message') if tcs else None
        usage=None
        for e in ls:
            p=e.get('payload',{})
            if p.get('type')=='token_count' and p.get('info'): usage=p['info']['total_token_usage']
        print('CHILD',name,'id',k,'started',st[0]['timestamp'] if st else None,'complete',tcs[-1]['timestamp'] if tcs else None,'last',repr(lm),'sha',sha(lm or ''),'usage',json.dumps(usage))
        # parent FINAL_ANSWER payload for this child
        for e in pl:
            p=e.get('payload',{})
            if p.get('type')=='agent_message' and p['author']==name:
                txt=p['content'][0]['text']; pay=txt.split('Payload:\n',1)[1]
                print('   parent FINAL_ANSWER payload sha',sha(pay),'equal_to_child_last',pay==lm)
