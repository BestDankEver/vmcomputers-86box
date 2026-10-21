gamemode creative @s
time set day
weather clear
gamerule doDaylightCycle false
tp @s 0.5 -60 0.5 0 15
summon mcvmcomputers:pc 0.5 -60 3.5
summon mcvmcomputers:crt_screen 2 -60 3.5
summon mcvmcomputers:flat_screen -1 -60 3.5
summon mcvmcomputers:walltv 0.5 -58 5.5
summon mcvmcomputers:keyboard 0.5 -60 2.2
summon mcvmcomputers:mouse 1.3 -60 2.2
give @s mcvmcomputers:ordering_tablet
give @s mcvmcomputers:crtscreen
give @s mcvmcomputers:pc_case
give @s mcvmcomputers:harddrive
give @s mcvmcomputers:motherboard{Part:{Id:"p55t2p4",Name:"[i430HX] ASUS P/I-P55T2P4",Type:18}}
give @s mcvmcomputers:cpu{Part:{Family:"pentium_p54c",Hz:133333333L,Multi:2.0d,Name:"Intel Pentium 133"}}
give @s mcvmcomputers:ram{Part:{Kb:16384}}
give @s mcvmcomputers:expansion_card{Part:{Category:"accel",Id:"voodoo",Name:"[PCI] 3Dfx Voodoo 2",Options:{type:"voodoo_2",framebuffer_memory:"4",texture_memory:"4",sli:"1"}}}
tag @s add smoked
