import json,sys
for fn in sys.argv[1:]:
    print("=====",fn)
    for l in open(fn):
        try:e=json.loads(l)
        except: continue
        t=e.get('type')
        if t=='system' and e.get('subtype')=='init': print("INIT model",e.get('model'),e.get('permissionMode'),"agents",e.get('agents'))
        if t=='assistant':
            p=e.get('parent_tool_use_id'); m=e['message']
            for c in m['content']:
                if c['type']=='tool_use': print("  TOOL_USE","SUB" if p else "PAR",m.get('model'),c['name'],json.dumps(c['input'],ensure_ascii=False)[:600])
                elif c['type']=='text': print("  TEXT","SUB" if p else "PAR",m.get('model'),repr(c['text'][:500]))
                elif c['type']=='thinking': print("  THINKING","SUB" if p else "PAR",m.get('model'),len(c.get('thinking','')))
        if t=='user':
            p=e.get('parent_tool_use_id'); tr=e.get('tool_use_result')
            m=e['message']['content']
            if isinstance(m,list):
              for c in m:
                if c.get('type')=='tool_result':
                    print("  RESULT","SUB" if p else "PAR",repr(str(c['content'])[:700]))
            if tr and not p: print("  TUR",json.dumps(tr,ensure_ascii=False)[:900])
        if t=='result': print("RESULT",e.get('result','')[:300].__repr__(),"so=",e.get('structured_output'),"denials",e.get('permission_denials'),"modelUsage",{k:(v['inputTokens'],v['outputTokens']) for k,v in e.get('modelUsage',{}).items()})
