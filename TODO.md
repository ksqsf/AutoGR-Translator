# unresolved

- inline class definition
- annotation
- override and dynamic typing
- array

# RegisterUser

path to Database.commit:

1. param[firstname]!=empty
2. param[lastname]!=empty
3. param[nickname]!=empty
4. param[email]!=empty
5. param[password]!=empty
6. param[region]!=empty
7. rs1=S0{query regions where name=param[region]}
8. rs1!=empty
9. regionId = rs1[0].id
10. rs2=S0{query users where nickname=param[nickname]}
11. rs2==empty
12. S1=S0{insert users ([id??], param[firstname], param[lastname], param[nickname], param[password], param[email], 0, 0, java[TimeManagement.currentDateToString()], regionId)}
13. rs3=S1{select id,creation_date from users where nickname=param[nickname]}
14. userId=rs3[0].id
15. creationDate=rs3[0].creation_date

side effect:
1. @.insert users

# StoreBuyNow


