import importlib.util, io, json, os, unittest
from pathlib import Path
from types import SimpleNamespace
from unittest.mock import patch
from urllib.error import HTTPError
spec=importlib.util.spec_from_file_location('server', Path(__file__).resolve().parents[1]/'server/linjian_server.py')
srv=importlib.util.module_from_spec(spec);spec.loader.exec_module(srv)
BODY={'device_id':'phone','messages':[{'role':'user','content':'hello'}]}
ENV={'STUDY_ROOM_FREE_AI_ENABLED':'true','STUDY_ROOM_FREE_AI_URL':'https://worker.example/api/study_room/reply','STUDY_ROOM_FREE_AI_TOKEN':'upstream-test'}
def call(data=BODY,token='test',headers=None):
 h=object.__new__(srv.Handler);h.path='/api/study_room/reply';h.state=SimpleNamespace(token='test')
 raw=(data if isinstance(data,str) else json.dumps(data)).encode()
 h.headers={'X-Auth-Token':token,'Content-Length':str(len(raw)),**(headers or {})};h.rfile=io.BytesIO(raw)
 h._json=lambda status,body:setattr(h,'output',(status,body))
 h.do_POST();return h.output
class ProxyTests(unittest.TestCase):
 def test_auth_and_validation(self):
  self.assertEqual(call(token='bad')[0],403)
  self.assertEqual(call(headers={'Content-Length':'40000'})[0],413)
  self.assertEqual(call(headers={'Content-Length':'no'})[0],400)
  for value in ('{',None,[],{},dict(BODY,device_id='../x'),dict(BODY,messages=[{'role':'system','content':'x'}])):
   self.assertEqual(call(value)[0],400)
 def test_default_disabled(self):
  with patch.dict(os.environ,{},clear=True),patch('urllib.request.build_opener') as p:
   self.assertEqual(call()[0],503);p.assert_not_called()
 def test_proxy_and_no_redirects(self):
  with patch.dict(os.environ,ENV,clear=True),patch('urllib.request.build_opener') as p:
   p.return_value.open.return_value.__enter__.return_value.read.return_value=json.dumps({'ok':True,'reply':'hello'}).encode()
   self.assertEqual(call(),(200,{'ok':True,'reply':'hello','provider':'free_ai_proxy','same_chatgpt_conversation':False}))
   redirect=p.call_args.args[0]
   self.assertIsNone(redirect.redirect_request(None,None,302,'',{},'https://evil.test'))
   req=p.return_value.open.call_args.args[0]
   self.assertEqual(req.get_header('X-auth-token'),'upstream-test')
   p.return_value.open.side_effect=HTTPError('https://worker.example',429,'quota',{},None)
   self.assertEqual(call()[0],429)
   p.return_value.open.side_effect=HTTPError('https://worker.example',302,'redirect',{},None)
   self.assertEqual(call()[0],503)
 def test_bad_upstream(self):
  for url in ['http://worker.example','https://u:p@worker.example','https://worker.example/?token=secret']:
   with patch.dict(os.environ,dict(ENV,STUDY_ROOM_FREE_AI_URL=url),clear=True),patch('urllib.request.build_opener') as p:
    self.assertEqual(call()[0],503);p.assert_not_called()
 def test_empty_and_oversized_reply(self):
  for raw in [b'{"ok":true,"reply":"  "}',b'x'*16385,b'[]']:
   with patch.dict(os.environ,ENV,clear=True),patch('urllib.request.build_opener') as p:
    p.return_value.open.return_value.__enter__.return_value.read.return_value=raw
    self.assertEqual(call()[0],503)
if __name__=='__main__':unittest.main()
