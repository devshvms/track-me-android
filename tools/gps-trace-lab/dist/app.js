"use strict";
const el = id => document.getElementById(id);
let lastReport = null;
function draw(report) {
  const canvas = el("plot"), ctx = canvas.getContext("2d"), origin = report.points[0];
  const pts = report.points.map(p => ({...p, x:(p.lon-origin.lon)*111195*Math.cos(origin.lat*Math.PI/180), y:(p.lat-origin.lat)*111195}));
  let minX=Infinity,minY=Infinity,maxX=-Infinity,maxY=-Infinity;
  for(const p of pts){minX=Math.min(minX,p.x);maxX=Math.max(maxX,p.x);minY=Math.min(minY,p.y);maxY=Math.max(maxY,p.y);}
  const scale=Math.min((canvas.width-70)/Math.max(1,maxX-minX),(canvas.height-70)/Math.max(1,maxY-minY));
  const x=p=>canvas.width/2+(p.x-(minX+maxX)/2)*scale, y=p=>canvas.height/2-(p.y-(minY+maxY)/2)*scale;
  ctx.clearRect(0,0,canvas.width,canvas.height);ctx.lineWidth=3;
  const after=report.stationaryAnnotation?.afterMinutes;
  for(let i=1;i<pts.length;i++){
    if(pts[i].segment!==pts[i-1].segment) continue;
    ctx.strokeStyle=after!==undefined&&pts[i].timeMillis-origin.timeMillis>=after*60000?"#efbb64":"#65d8e8";
    ctx.beginPath();ctx.moveTo(x(pts[i-1]),y(pts[i-1]));ctx.lineTo(x(pts[i]),y(pts[i]));ctx.stroke();
  }
  ctx.fillStyle="#e8f2fa";ctx.font="18px system-ui";ctx.fillText("Relative geometry · no external map",20,30);
}
function render(report){
  lastReport=report;draw(report);el("metrics").replaceChildren();
  const rows=[["Recorded points",report.pointCount],["Elapsed time",(report.elapsedSeconds/60).toFixed(1)+" min"],["Raw chord length — not activity distance",(report.rawChordMeters/1000).toFixed(3)+" km"],["Intervals over 15 seconds",report.gapsOver15Seconds]];
  if(report.stationaryAnnotation){rows.push(["Annotated stationary points",report.stationaryAnnotation.points],["Stationary cloud radius (median centre)",report.stationaryAnnotation.radiusMeters.toFixed(1)+" m"]);}
  for(const [label,value] of rows){const row=document.createElement("div"),strong=document.createElement("strong");row.textContent=label;strong.textContent=String(value);row.append(strong);el("metrics").append(row);}
  el("saved").textContent="Saved for Codex: "+report.savedDirectory;
  el("status").textContent="Imported. Cyan: route. Amber: your annotated stationary interval. Original evidence retained.";
}
el("upload").addEventListener("submit",async event=>{
  event.preventDefault();const file=el("file").files[0];if(!file)return;
  if(file.size>10*1024*1024){el("status").textContent="Choose a GPX smaller than 10 MB.";return;}
  el("submit").disabled=true;el("status").textContent="Reading and saving locally…";
  try{
    const query=new URLSearchParams({persona:el("persona").value,stationaryAfter:el("after").value});
    const response=await fetch("/api/traces?"+query,{method:"POST",headers:{"Content-Type":"application/gpx+xml","X-TrackMe-Upload":"local"},body:file});
    const report=await response.json();if(!response.ok)throw new Error(report.error||"Import failed.");render(report);
  }catch(error){el("status").textContent=error.message+" No recording was changed.";}
  finally{el("submit").disabled=false;}
});
if(document.modelContext?.registerTool){
  const lifecycle=new AbortController();
  try{Promise.resolve(document.modelContext.registerTool({name:"read_trace_summary",description:"Read the summary of the GPX explicitly imported on this page, without coordinates.",inputSchema:{type:"object",properties:{},additionalProperties:false},annotations:{readOnlyHint:true,untrustedContentHint:true},execute(input){if(Object.keys(input||{}).length)throw new Error("No input fields accepted.");if(!lastReport)return {imported:false};const {points,...summary}=lastReport;return summary;}},{signal:lifecycle.signal})).catch(()=>{});}catch{}
  window.addEventListener("pagehide",()=>lifecycle.abort(),{once:true});
}
