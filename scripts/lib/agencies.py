"""samples 현황 엑셀 상세 시트에서 '기관명 매입사 발급사' 목록을 출력한다."""
import sys
import openpyxl

ws = next(s for s in openpyxl.load_workbook(sys.argv[1]).worksheets if "(상세)" in s.title)
for r in ws.iter_rows(min_row=6, max_row=60, min_col=2, max_col=4, values_only=True):
    if not r[2]:
        break
    print(r[2], str(r[0]).zfill(3), str(r[1]).zfill(3))
