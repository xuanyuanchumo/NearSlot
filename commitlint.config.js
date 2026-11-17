// Git 规范 §1：type(scope): 中文 subject ≤50 字，祈使句无句号
module.exports = {
  extends: ['@commitlint/config-conventional'],
  rules: {
    'type-enum': [2, 'always', ['feat','fix','docs','style','refactor','perf','test','chore','ci','revert']],
    'scope-enum': [2, 'always', ['interceptor','service','controller','mapper','entity','utils','config','cache','lua','security','dto','common','auth','shop','appointment','order','agent','framework','docs']],
    'subject-max-length': [2, 'always', 50],
    'header-max-length': [2, 'always', 100],
  },
};
