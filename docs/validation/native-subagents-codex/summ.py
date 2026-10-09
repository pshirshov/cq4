import json,glob,hashlib,os
H='/tmp/cxq/home/sessions'
files={}
for f in glob.glob(H+'/**/*.jsonl',recursive=True):
    ls=[json.loads(l) for l in open(f)]; files[ls[0]['payload']['id']]=ls
def sha(s): return hashlib.sha256((s or '').encode()).hexdigest()[:12]
def ctx(ls):
    c=[e['payload'] for e in ls if e['type']=='turn_context'][0]
    return {'cwd':c['cwd'],'model':c['model'],'effort':c.get('effort'),'approval':c['approval_policy'],'sandbox':c['sandbox_policy']['type']}
def last(ls):
    r=[e['payload'].get('last_agent_message') for e in ls if e.get('payload',{}).get('type')=='task_complete']
    return r[-1] if r else None
def usage(ls):
    u=None
    for e in ls:
        p=e.get('payload',{})
        if p.get('type')=='token_count' and p.get('info'): u=p['info']['total_token_usage']
    return u and {'input':u['input_tokens'],'output':u['output_tokens'],'total':u['total_tokens']}
for f in sorted(glob.glob('/tmp/cxq/out/*.jsonl'),key=lambda x:os.path.getmtime(x)):
    name=os.path.basename(f)[:-6]
    tid=None
    for l in open(f):
        try: e=json.loads(l)
        except: continue
        if e.get('type')=='thread.started': tid=e['thread_id']; break
        break
    if tid not in files: continue
    ls=files[tid]
    print('RUN',name,'parent',tid[-12:],json.dumps(ctx(ls)),'rollout_usage',json.dumps(usage(ls)))
    for k,kl in files.items():
        s=kl[0]['payload'].get('source')
        if isinstance(s,dict) and s['subagent']['thread_spawn']['parent_thread_id']==tid:
            sp=s['subagent']['thread_spawn']
            print('  CHILD',sp['agent_path'],'depth',sp['depth'],'id',k[-12:],json.dumps(ctx(kl)),'session_id_is_parent',kl[0]['payload']['session_id']==tid,'provider',kl[0]['payload']['model_provider'],'usage',json.dumps(usage(kl)))
            lm=last(kl)
            fa=[e['payload']['content'][0]['text'].split('Payload:\n',1)[1] for e in ls if e.get('payload',{}).get('type')=='agent_message' and e['payload']['author']==sp['agent_path']]
            print('     child_last',json.dumps(lm)[:240],'sha',sha(lm),'| parent_FINAL_ANSWER_sha',[sha(x) for x in fa],'equal',[x==lm for x in fa])
