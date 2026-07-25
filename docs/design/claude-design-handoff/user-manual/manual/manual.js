// design-doc affordances: copy confirmations only.
const LBL={link:['Copy link','Link copied'],ai:['Copy for AI','Copied for AI'],cmd:['Copy command','Command copied']};
document.querySelectorAll('[data-copy]').forEach(b=>{
  const k=b.dataset.copy,sp=b.querySelector('span'),base=sp?sp.textContent:'';
  b.addEventListener('click',()=>{
    if(b.dataset.busy)return;b.dataset.busy='1';
    const ic=b.querySelector('use');const prev=ic&&ic.getAttribute('href');
    if(ic)ic.setAttribute('href','#i-check');
    if(sp)sp.textContent=(LBL[k]&&LBL[k][1])||'Copied';
    b.classList.add('done');
    setTimeout(()=>{if(ic&&prev)ic.setAttribute('href',prev);if(sp)sp.textContent=base;b.classList.remove('done');b.dataset.busy='';},2400);
  });
});
const f2=document.getElementById('f2copy');
if(f2){const sp=f2.querySelector('span'),ic=f2.querySelector('use');
  f2.addEventListener('click',()=>{
    if(f2.dataset.busy)return;f2.dataset.busy='1';
    ic.setAttribute('href','#i-check');sp.textContent='Manual link copied';f2.style.color='#4FB477';
    setTimeout(()=>{ic.setAttribute('href','#i-ai');sp.textContent='Copy link for AI';f2.style.color='#9BA1A6';f2.dataset.busy='';},2400);
  });}
