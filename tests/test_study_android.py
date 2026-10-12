"""Run production timer/chat classes on a JVM with real org.json and small Context/preferences fakes.
Set JSON_JAR to org.json:json:20240303 (test dependency only).
"""
from pathlib import Path
import os, subprocess, tempfile
root=Path(__file__).resolve().parents[1]
jar=Path(os.environ['JSON_JAR']).resolve()
with tempfile.TemporaryDirectory() as d:
 p=Path(d)
 def put(name,text):
  f=p/name;f.parent.mkdir(parents=True,exist_ok=True);f.write_text(text)
 put('android/content/Context.java','package android.content; public class Context {}')
 put('dev/linjian/peek/AppPrefs.java','''package dev.linjian.peek;
import android.content.Context; import java.util.HashMap;
public class AppPrefs {
 static final Pref prefs = new Pref(); public static Pref get(Context c){return prefs;}
 public static class Pref {
  HashMap<String,Object> data = new HashMap<>();
  public String getString(String k,String v){return (String)data.getOrDefault(k,v);}
  public boolean getBoolean(String k,boolean v){return (Boolean)data.getOrDefault(k,v);}
  public Pref edit(){return this;} public Pref putString(String k,String v){data.put(k,v);return this;}
  public Pref putBoolean(String k,boolean v){data.put(k,v);return this;} public void apply(){}
 }
}''')
 for name in ('StudyTimer.java','StudyRoomChat.java'):
  put('dev/linjian/peek/'+name,(root/'android/app/src/main/java/dev/linjian/peek'/name).read_text())
 put('dev/linjian/peek/StudyTest.java','''package dev.linjian.peek;
import android.content.Context; import org.json.*; import java.util.*; import java.lang.reflect.*;
public class StudyTest {
 static Context c=new Context(); static int checks=0;
 static void check(boolean b,String s){checks++;if(!b)throw new AssertionError(s);}
 static JSONObject command(String action){return StudyTimer.handleCommand(c,new JSONObject().put("action",action).put("subject","英语"));}
 public static void main(String[] args)throws Exception{
  TimeZone.setDefault(TimeZone.getTimeZone("Asia/Shanghai"));
  check(StudyTimer.snapshot(c).getString("status").equals("idle"),"initial idle");
  check(!command("pause_study_timer").getBoolean("ok"),"cannot pause idle");
  check(command("start_study_timer").getBoolean("ok"),"start");
  check(!command("start_study_timer").getBoolean("ok"),"reject duplicate start");
  JSONObject state=new JSONObject(AppPrefs.get(c).getString("study_timer_state_v1",""));
  state.put("segment_started_at_ms",System.currentTimeMillis()-120000);
  AppPrefs.get(c).edit().putString("study_timer_state_v1",state.toString()).apply();
  long first=StudyTimer.snapshot(c).getLong("today_ms");
  check(first>=120000 && first<121000,"running credited dynamically");
  check(StudyTimer.snapshot(c).getLong("today_ms")<121000,"read never double credits");
  check(command("pause_study_timer").getBoolean("ok"),"pause");
  long paused=StudyTimer.snapshot(c).getLong("elapsed_ms");
  check(StudyTimer.snapshot(c).getLong("elapsed_ms")==paused,"paused is fixed");
  check(command("resume_study_timer").getBoolean("ok"),"resume");
  check(command("stop_study_timer").getBoolean("ok"),"stop");
  check(!command("stop_study_timer").getBoolean("ok"),"cannot save twice");
  check(StudyTimer.snapshot(c).getJSONObject("today_subjects_ms").getLong("英语")>=120000,"subject total");
  check(StudyTimer.snapshot(c).getInt("today_sessions")==1,"one session");
  Method credit=StudyTimer.class.getDeclaredMethod("creditDays",JSONObject.class,long.class,long.class);credit.setAccessible(true);
  Calendar cal=Calendar.getInstance();cal.set(2026,9,11,23,59,0);cal.set(Calendar.MILLISECOND,0);
  JSONObject days=new JSONObject();credit.invoke(null,days,cal.getTimeInMillis(),cal.getTimeInMillis()+120000);
  check(days.getLong("2026-10-11")==60000 && days.getLong("2026-10-12")==60000,"midnight split");
  StudyRoomChat.append(c,"user","local message");
  check(StudyRoomChat.snapshot(c).getJSONArray("recent_messages").length()==0,"sync off by default");
  check(!AppPrefs.get(c).getBoolean(StudyRoomChat.AI_KEY,false),"AI off by default");
  AppPrefs.get(c).edit().putBoolean(StudyRoomChat.SYNC_KEY,true).apply();
  check(StudyRoomChat.snapshot(c).getJSONArray("recent_messages").length()==1,"sync opt-in");
  for(int i=0;i<80;i++)StudyRoomChat.append(c,"user","message "+i);
  check(StudyRoomChat.history(c).length()==60,"history capped");
  check(StudyRoomChat.snapshot(c).getJSONArray("recent_messages").length()==12,"sync capped");
  for(int i=0;i<8;i++)StudyRoomChat.append(c,"system","network unavailable");
  check(StudyRoomChat.contextForAi(c).length()==8,"last eight valid messages, excluding system");
  check(StudyRoomChat.contextForAi(c).getJSONObject(7).getString("role").equals("user"),"last is user");
  StudyRoomChat.append(c,"user","x".repeat(1500));
  check(StudyRoomChat.history(c).getJSONObject(59).getString("text").length()==1000,"message bound");
  AppPrefs.get(c).edit().putBoolean(StudyRoomChat.SYNC_KEY,false).apply();
  check(StudyRoomChat.snapshot(c).getJSONArray("recent_messages").length()==0,"disable clears next snapshot");
  System.out.println("PASS: "+checks+" timer, midnight, subjects, persistence and chat privacy assertions");
 }
}''')
 subprocess.run(['javac','-encoding','UTF-8','-cp',str(jar),'-d',str(p/'classes')]+[str(x) for x in p.rglob('*.java')],check=True)
 subprocess.run(['java','-cp',os.pathsep.join([str(p/'classes'),str(jar)]),'dev.linjian.peek.StudyTest'],check=True)
