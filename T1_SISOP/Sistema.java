// PUCRS - Escola Politécnica - Sistemas Operacionais
// Prof. Fernando Dotti
// Código fornecido como parte da solução do projeto de Sistemas Operacionais
//
// VM
//    HW = memória, cpu
//    SW = tratamento int e chamada de sistema
// Funcionalidades de carga, execução e dump de memória

import java.util.*;
import java.util.concurrent.Semaphore;

public class Sistema {

	// -------------------------------------------------------------------------------------------------------
	// --------------------- H A R D W A R E - definicoes de HW
	// ----------------------------------------------

	// -------------------------------------------------------------------------------------------------------
	// --------------------- M E M O R I A - definicoes de palavra de memoria,
	// memória ----------------------

	public class Memory {
		public int tamMem;
		public Word[] m; // m representa a memória fisica: um array de posicoes de memoria (word)

		public Memory(int size) {
			tamMem = size;

			m = new Word[tamMem];

			for (int i = 0; i < tamMem; i++) {
				m[i] = new Word(Opcode.___, -1, -1, -1);
			}
			;
		}

		public void dump(Word w) { // funcoes de DUMP nao existem em hardware - colocadas aqui para facilidade
			System.out.print("[ ");
			System.out.print(w.opc);
			System.out.print(", ");
			System.out.print(w.r1);
			System.out.print(", ");
			System.out.print(w.r2);
			System.out.print(", ");
			System.out.print(w.p);
			System.out.println("  ] ");
		}

		public void dump(int ini, int fim) {
			for (int i = ini; i < fim; i++) {
				if (m[i].opc != Opcode.___) {
					System.out.print(i);
					System.out.print(":  ");
					dump(m[i]);
				}
			}
		}
	}

	// -------------------------------------------------------------------------------------------------------

	public class Word { // cada posicao da memoria tem uma instrucao (ou um dado)
		public Opcode opc; //
		public int r1; // indice do primeiro registrador da operacao (Rs ou Rd cfe opcode na tabela)
		public int r2; // indice do segundo registrador da operacao (Rc ou Rs cfe operacao)
		public int p; // parametro para instrucao (k ou A cfe operacao), ou o dado, se opcode = DADO

		public Word(Opcode _opc, int _r1, int _r2, int _p) { // vide definição da VM - colunas vermelhas da tabela
			opc = _opc;
			r1 = _r1;
			r2 = _r2;
			p = _p;
		}
	}

	// -------------------------------------------------------------------------------------------------------
	// --------------------- C P U - definicoes da CPU
	// -----------------------------------------------------

	public enum Opcode {
		DATA, ___, // se memoria nesta posicao tem um dado, usa DATA, se nao usada ee NULO ___
		JMP, JMPI, JMPIG, JMPIL, JMPIE, // desvios e parada
		JMPIM, JMPIGM, JMPILM, JMPIEM, STOP,
		JMPIGK, JMPILK, JMPIEK, JMPIGT,
		ADDI, SUBI, ADD, SUB, MULT, // matematicos
		LDI, LDD, STD, LDX, STX, MOVE, // movimentacao
		TRAP, SYSCALL // chamada de sistema
	}

	public enum Interrupts { // possiveis interrupcoes que esta CPU gera
		noInterrupt, intEnderecoInvalido, intInstrucaoInvalida, intOverflow, intSTOP, intTimeOut;
	}

	public class CPU {
		private int maxInt; // valores maximo e minimo para inteiros nesta cpu
		private int minInt;
		// característica do processador: contexto da CPU ...
		private int pc; // ... composto de program counter,
		private Word ir; // instruction register,
		private int[] reg; // registradores da CPU
		private Interrupts irpt; // durante instrucao, interrupcao pode ser sinalizada
		private int base; // base e limite de acesso na memoria
		private int limite; // por enquanto toda memoria pode ser acessada pelo processo rodando
							// ATE AQUI: contexto da CPU - tudo que precisa sobre o estado de um processo
							// para executa-lo
							// nas proximas versoes isto pode modificar

		private Memory mem; // mem tem funcoes de dump e o array m de memória 'fisica'
		private Word[] m; // CPU acessa MEMORIA, guarda referencia a 'm'. m nao muda. semre será um array
							// de palavras

		private InterruptHandling ih; // significa desvio para rotinas de tratamento de Int - se int ligada, desvia
		private SysCallHandling sysCall; // significa desvio para tratamento de chamadas de sistema - trap
		private boolean debug; // se true entao mostra cada instrucao em execucao

		public CPU(Memory _mem, InterruptHandling _ih, SysCallHandling _sysCall, boolean _debug) { // ref a MEMORIA e
																									// interrupt handler
																									// passada na
																									// criacao da CPU
			maxInt = 32767; // capacidade de representacao modelada
			minInt = -32767; // se exceder deve gerar interrupcao de overflow
			mem = _mem; // usa mem para acessar funcoes auxiliares (dump)
			m = mem.m; // usa o atributo 'm' para acessar a memoria.
			reg = new int[10]; // aloca o espaço dos registradores - regs 8 e 9 usados somente para IO
			ih = _ih; // aponta para rotinas de tratamento de int
			sysCall = _sysCall; // aponta para rotinas de tratamento de chamadas de sistema
			debug = _debug; // se true, print da instrucao em execucao
		}

		private boolean legal(int e, ArrayList<Integer> framesAlocados) { // todo acesso a memoria tem que ser
																			// verificado
			for (int i = 0; i < framesAlocados.size(); i++) {
				int frame = framesAlocados.get(i);
				int tamMaximo = (frame * vm.gm.tamPag) + vm.gm.tamPag - 1;
				int tamMinimo = frame * vm.gm.tamPag;
				if (e <= tamMaximo && e >= tamMinimo) {
					return true;
				}
			}
			irpt = Interrupts.intEnderecoInvalido;
			return false;
		}

		private boolean testOverflow(int v) { // toda operacao matematica deve avaliar se ocorre overflow
			if ((v < minInt) || (v > maxInt)) {
				irpt = Interrupts.intOverflow;
				return false;
			}
			;
			return true;
		}

		public void setContext(int _base, int _limite, int _pc) { // no futuro esta funcao vai ter que ser
			base = _base; // expandida para setar todo contexto de execucao,
			limite = _limite; // agora, setamos somente os registradores base,
			pc = _pc; // limite e pc (deve ser zero nesta versao)
			irpt = Interrupts.noInterrupt; // reset da interrupcao registrada
		}

		public void run(PCB pcb) { // execucao da CPU supoe que o contexto da CPU, vide acima,
									// esta devidamente
			// setado
			int cont = 0;

			ArrayList<Integer> framesAlocados = pcb.framesAlocados;

			while (true) { // ciclo de instrucoes. acaba cfe instrucao, veja cada caso.
				// --------------------------------------------------------------------------------------------------
				// FETCH

				System.out.println("PCB PC: " + pcb.pc);
				pc = pcb.pc;
				reg = pcb.statusReg;

				// System.out.println("Reg Length: " + reg.length);

				int pcTraduzido = tradutorEndereco(pc, framesAlocados);

				if (legal(pcTraduzido, framesAlocados)) { // pc valido
					ir = m[pcTraduzido]; // <<<<<<<<<<<< busca posicao da memoria
											// apontada
					// por pc,
					// guarda
					// em ir
					if (debug) {
						System.out.print("                               pc: " + pc + "       exec: ");
						mem.dump(ir);
					}
					// --------------------------------------------------------------------------------------------------
					// EXECUTA INSTRUCAO NO ir
					switch (ir.opc) { // conforme o opcode (código de operação) executa

						// Instrucoes de Busca e Armazenamento em Memoria
						case LDI: // Rd ← k
							reg[ir.r1] = ir.p;
							pcb.statusReg[ir.r1] = ir.p;
							pc++;
							pcb.pc++;
							cont++;
							if (cont == 2)
								irpt = Interrupts.intTimeOut;
							break;

						case LDD: // Rd <- [A]
							if (legal(ir.p, framesAlocados)) {
								reg[ir.r1] = m[tradutorEndereco(ir.p, framesAlocados)].p;
								pcb.statusReg[ir.r1] = m[tradutorEndereco(ir.p, framesAlocados)].p;
								pc++;
								pcb.pc++;
							}
							cont++;
							if (cont == 2)
								irpt = Interrupts.intTimeOut;
							break;

						case LDX: // RD <- [RS] // NOVA
							if (legal(reg[ir.r2], framesAlocados)) {
								reg[ir.r1] = m[tradutorEndereco(reg[ir.r2], framesAlocados)].p;
								pcb.statusReg[ir.r1] = m[tradutorEndereco(reg[ir.r2], framesAlocados)].p;
								pc++;
								pcb.pc++;
							}
							cont++;
							if (cont == 2)
								irpt = Interrupts.intTimeOut;
							break;

						case STD: // [A] ← Rs
							System.out.println("IR.P: " + ir.p);
							System.out.println("ENDEREÇO TRADUZIDO: " + tradutorEndereco(ir.p, framesAlocados));
							System.out.println("REGISTRADOR: " + reg[ir.r1]);
							if (legal(tradutorEndereco(ir.p, framesAlocados), framesAlocados)) {
								m[tradutorEndereco(ir.p, framesAlocados)].opc = Opcode.DATA;
								m[tradutorEndereco(ir.p, framesAlocados)].p = reg[ir.r1];
								pc++;
								pcb.pc++;
							}
							cont++;
							if (cont == 2)
								irpt = Interrupts.intTimeOut;
							break;

						case STX: // [Rd] ←Rs
							System.out.println("COISO:" + reg[ir.r1]);
							if(legal(reg[ir.r1], framesAlocados)){
								m[tradutorEndereco(reg[ir.r1], framesAlocados)].opc = Opcode.DATA;
								m[tradutorEndereco(reg[ir.r1], framesAlocados)].p = reg[ir.r2];
								pc++;
								pcb.pc++;
							}
							cont++;
							if (cont == 2)
								irpt = Interrupts.intTimeOut;
							break;

						case MOVE: // RD <- RS
							reg[ir.r1] = reg[ir.r2];
							pcb.statusReg[ir.r1] = reg[ir.r2];
							pc++;
							pcb.pc++;
							cont++;
							if (cont == 2)
								irpt = Interrupts.intTimeOut;
							break;

						// Instrucoes Aritmeticas
						case ADD: // Rd ← Rd + Rs
							reg[ir.r1] = reg[ir.r1] + reg[ir.r2];
							pcb.statusReg[ir.r1] = reg[ir.r1];
							testOverflow(reg[ir.r1]);
							pc++;
							pcb.pc++;
							cont++;
							if (cont == 2)
								irpt = Interrupts.intTimeOut;
							break;

						case ADDI: // Rd ← Rd + k
							reg[ir.r1] = reg[ir.r1] + ir.p;
							pcb.statusReg[ir.r1] = reg[ir.r1];
							testOverflow(reg[ir.r1]);
							pc++;
							pcb.pc++;
							cont++;
							if (cont == 2)
								irpt = Interrupts.intTimeOut;
							break;

						case SUB: // Rd ← Rd - Rs
							reg[ir.r1] = reg[ir.r1] - reg[ir.r2];
							pcb.statusReg[ir.r1] = reg[ir.r1];
							testOverflow(reg[ir.r1]);
							pc++;
							pcb.pc++;
							cont++;
							if (cont == 2)
								irpt = Interrupts.intTimeOut;
							break;

						case SUBI: // RD <- RD - k // NOVA
							reg[ir.r1] = reg[ir.r1] - ir.p;
							pcb.statusReg[ir.r1] = reg[ir.r1];
							testOverflow(reg[ir.r1]);
							pc++;
							pcb.pc++;
							cont++;
							if (cont == 2)
								irpt = Interrupts.intTimeOut;
							break;

						case MULT: // Rd <- Rd * Rs
							reg[ir.r1] = reg[ir.r1] * reg[ir.r2];
							pcb.statusReg[ir.r1] = reg[ir.r1];
							testOverflow(reg[ir.r1]);
							pc++;
							pcb.pc++;
							cont++;
							if (cont == 2)
								irpt = Interrupts.intTimeOut;
							break;

						// Instrucoes JUMP
						case JMP: // PC <- k
							pc = ir.p;
							pcb.pc = ir.p;
							cont++;
							if (cont == 2)
								irpt = Interrupts.intTimeOut;
							break;

						case JMPIG: // If Rc > 0 Then PC ← Rs Else PC ← PC +1
							if (reg[ir.r2] > 0) {
								pc = reg[ir.r1];
								pcb.pc = reg[ir.r1];
							} else {
								pc++;
								pcb.pc++;
							}
							cont++;
							if (cont == 2)
								irpt = Interrupts.intTimeOut;
							break;

						case JMPIGK: // If RC > 0 then PC <- k else PC++
							if (reg[ir.r2] > 0) {
								pc = ir.p;
								pcb.pc = ir.p;
							} else {
								pc++;
								pcb.pc++;
							}
							cont++;
							if (cont == 2)
								irpt = Interrupts.intTimeOut;
							break;

						case JMPILK: // If RC < 0 then PC <- k else PC++
							if (reg[ir.r2] < 0) {
								pc = ir.p;
								pcb.pc = ir.p;
							} else {
								pc++;
								pcb.pc++;
							}
							cont++;
							if (cont == 2)
								irpt = Interrupts.intTimeOut;
							break;

						case JMPIEK: // If RC = 0 then PC <- k else PC++
							if (reg[ir.r2] == 0) {
								pc = ir.p;
								pcb.pc = ir.p;
							} else {
								pc++;
								pcb.pc++;
							}
							cont++;
							if (cont == 2)
								irpt = Interrupts.intTimeOut;

							break;

						case JMPIL: // if Rc < 0 then PC <- Rs Else PC <- PC +1
							if (reg[ir.r2] < 0) {
								pc = reg[ir.r1];
								pcb.pc = reg[ir.r1];
							} else {
								pc++;
								pcb.pc++;
							}
							cont++;
							if (cont == 2)
								irpt = Interrupts.intTimeOut;
							break;

						case JMPIE: // If Rc = 0 Then PC <- Rs Else PC <- PC +1
							if (reg[ir.r2] == 0) {
								pc = reg[ir.r1];
								pcb.pc = reg[ir.r1];
							} else {
								pc++;
								pcb.pc++;
							}
							cont++;
							if (cont == 2)
								irpt = Interrupts.intTimeOut;
							break;

						case JMPIM: // PC <- [A]
							pc = m[tradutorEndereco(ir.p, framesAlocados)].p;
							cont++;
							if (cont == 2)
								irpt = Interrupts.intTimeOut;
							break;

						case JMPIGM: // If RC > 0 then PC <- [A] else PC++
							if (reg[ir.r2] > 0) {
								pc = m[tradutorEndereco(ir.p, framesAlocados)].p;
								pcb.pc = m[tradutorEndereco(ir.p, framesAlocados)].p;
							} else {
								pc++;
								pcb.pc++;
							}
							cont++;
							if (cont == 2)
								irpt = Interrupts.intTimeOut;
							break;

						case JMPILM: // If RC < 0 then PC <- k else PC++
							if (reg[ir.r2] < 0) {
								pc = m[tradutorEndereco(ir.p, framesAlocados)].p;
								pcb.pc = m[tradutorEndereco(ir.p, framesAlocados)].p;
							} else {
								pc++;
								pcb.pc++;
							}
							cont++;
							if (cont == 2)
								irpt = Interrupts.intTimeOut;
							break;

						case JMPIEM: // If RC = 0 then PC <- k else PC++
							if (reg[ir.r2] == 0) {
								pc = m[tradutorEndereco(ir.p, framesAlocados)].p;
								pcb.pc = m[tradutorEndereco(ir.p, framesAlocados)].p;
							} else {
								pc++;
								pcb.pc++;
							}
							cont++;
							if (cont == 2)
								irpt = Interrupts.intTimeOut;
							break;

						case JMPIGT: // If RS>RC then PC <- k else PC++
							if (reg[ir.r1] > reg[ir.r2]) {
								pc = ir.p;
								pcb.pc = m[tradutorEndereco(ir.p, framesAlocados)].p;
							} else {
								pc++;
								pcb.pc++;
							}
							cont++;
							if (cont == 2)
								irpt = Interrupts.intTimeOut;
							break;

						// outras
						case STOP: // por enquanto, para execucao
							irpt = Interrupts.intSTOP;
							break;

						case DATA:
							irpt = Interrupts.intInstrucaoInvalida;
							break;

						// Chamada de sistema
						case TRAP:
							sysCall.handle(pcb); // <<<<< aqui desvia para rotina de chamada de sistema, no momento so
												// temos IO
							pc++;
							pcb.pc++;
							cont++;
							if (cont == 2)
								irpt = Interrupts.intTimeOut;
							break;

						case SYSCALL:
							sysCall.handle(pcb);
							pc++;
							pcb.pc++;
							cont++;
							if (cont == 2)
								irpt = Interrupts.intTimeOut;
							break;

						// Inexistente
						default:
							irpt = Interrupts.intInstrucaoInvalida;
							break;
					}
				}

				// --------------------------------------------------------------------------------------------------
				// VERIFICA INTERRUPÇÃO !!! - TERCEIRA FASE DO CICLO DE INSTRUÇÕES
				if (!(irpt == Interrupts.noInterrupt)) { // existe interrupção
					ih.handle(irpt, pc, pcb); // desvia para rotina de tratamento
					break; // break sai do loop da cpu
				}
			} // FIM DO CICLO DE UMA INSTRUÇÃO
		}
	}
	// ------------------ C P U - fim
	// ------------------------------------------------------------------------
	// -------------------------------------------------------------------------------------------------------

	// ------------------- V M - constituida de CPU e MEMORIA
	// -----------------------------------------------
	// -------------------------- atributos e construcao da VM
	// -----------------------------------------------
	public class VM {
		public int tamMem;
		public int tamPag;
		public Word[] m;
		public Memory mem;
		public CPU cpu;
		public GM gm;
		public GP gp;

		public VM(InterruptHandling ih, SysCallHandling sysCall) {
			// vm deve ser configurada com endereço de tratamento de interrupcoes e de
			// chamadas de sistema
			// cria memória
			tamMem = 1024;
			tamPag = 8;
			gp = new GP();
			mem = new Memory(tamMem);
			m = mem.m; // RETORNAR A PAGINA LIVRE PRA ALOCAR O PROGRAMA
			gm = new GM(mem, tamMem, tamPag);

			// cria cpu
			cpu = new CPU(mem, ih, sysCall, true); // true liga debug

		}
	}
	// ------------------- V M - fim
	// ------------------------------------------------------------------------
	// -------------------------------------------------------------------------------------------------------

	// --------------------H A R D W A R E - fim
	// -------------------------------------------------------------
	// -------------------------------------------------------------------------------------------------------

	// -------------------------------------------------------------------------------------------------------

	// -------------------------------------------------------------------------------------------------------
	// -------------------------------------------------------------------------------------------------------
	// -------------------------------------------------------------------------------------------------------
	// ------------------- S O F T W A R E - inicio
	// ----------------------------------------------------------

	// ------------------- I N T E R R U P C O E S - rotinas de tratamento
	// ----------------------------------
	public class InterruptHandling {
		public void handle(Interrupts irpt, int PC, PCB pcb) { // apenas avisa - todas interrupcoes neste momento
																// finalizam o
			// programa
			System.out.println("                                               Interrupcao " + irpt + "   pc: " + PC);

			if (irpt == Interrupts.intTimeOut) {
				// pcb.pc = PC;
				pcb.estado = "PRONTO";
				irpt = Interrupts.noInterrupt;

			} else if (irpt == Interrupts.intSTOP) {
				pcb.estado = "FINALIZADO";
				irpt = Interrupts.noInterrupt;

			} else {
				pcb.estado = "FINALIZADO";
			}

		}
	}

	// ------------------- C H A M A D A S D E S I S T E M A - rotinas de tratamento
	// ----------------------
	public class SysCallHandling {
		private VM vm;

		public void setVM(VM _vm) {
			vm = _vm;
		}

		public void handle(PCB pcb) { // apenas avisa - todas interrupcoes neste momento finalizam o programa
			Scanner scanner = new Scanner(System.in);
			System.out.println("                                               Chamada de Sistema com op  /  par:  "
					+ vm.cpu.reg[8] + " / " + vm.cpu.reg[9]);
			pcb.estado = "BLOQUEADO";
			System.out.println("O processo" + pcb.id + "precisa de um retorno numérico do dispositivo");
			int num = scanner.nextInt();

		}
	}

	// ------------------ U T I L I T A R I O S D O S I S T E M A
	// -----------------------------------------
	// ------------------ load é invocado a partir de requisição do usuário
	private void loadProgram(Word[] p, Word[] m) {
		for (int i = 0; i < p.length; i++) {
			m[tradutorEnderecoCriar(i)].opc = p[i].opc;
			m[tradutorEnderecoCriar(i)].r1 = p[i].r1;
			m[tradutorEnderecoCriar(i)].r2 = p[i].r2;
			m[tradutorEnderecoCriar(i)].p = p[i].p;
		}
	}

	private void clearMemoria(Word[] p, Word[] m, ArrayList<Integer> framesAlocados) {
		for (int i = 0; i < framesAlocados.size(); i++) {
			int frame = framesAlocados.get(i);
			int inicioFrame = frame * vm.gm.tamPag;
			int finalFrame = (frame * vm.gm.tamPag) + vm.gm.tamPag;
			for (int k = inicioFrame; k < finalFrame; k++) {
				m[k].opc = Opcode.___;
				m[k].r1 = -1;
				m[k].r2 = -1;
				m[k].p = -1;
			}
		}
	}

	public int tradutorEnderecoCriar(int posicaoLogica) {

		ArrayList<Integer> framesAlocados = vm.gm.framesAlocados;

		int emQPaginaEstou = posicaoLogica / vm.tamPag;

		int offset = posicaoLogica % vm.tamPag;

		int emQFrameEstou = framesAlocados.get(emQPaginaEstou).intValue();

		int inicioFrame = emQFrameEstou * vm.gm.tamPag;

		return inicioFrame + offset;
	}

	public int tradutorEndereco(int posicaoLogica, ArrayList<Integer> framesAlocados) {

		System.out.println("POSIÇÃO LÓGICA: " + posicaoLogica);

		int emQPaginaEstou = posicaoLogica / vm.tamPag;

		int offset = posicaoLogica % vm.tamPag;

		int emQFrameEstou = framesAlocados.get(emQPaginaEstou).intValue();

		int inicioFrame = emQFrameEstou * vm.gm.tamPag;

		return (inicioFrame + offset);
	}

	private void loadProgram(Word[] p) {
		loadProgram(p, vm.m);
	}

	private void clearMemoria(Word[] p, ArrayList<Integer> framesAlocados) {
		clearMemoria(p, vm.m, framesAlocados);
	}

	// private void exec(Word[] p) {
	// vm.cpu.setContext(0, vm.tamMem - 1, 0); // seta estado da cpu ]
	// System.out.println("---------------------------------- inicia execucao ");
	// vm.cpu.run(); // cpu roda programa ate parar

	// }

	// -------------------------------------------------------------------------------------------------------
	// ------------------- S I S T E M A
	// --------------------------------------------------------------------

	public VM vm;
	public InterruptHandling ih;
	public SysCallHandling sysCall;
	public static Programas progs;

	public Sistema() { // a VM com tratamento de interrupções
		ih = new InterruptHandling();
		sysCall = new SysCallHandling();
		vm = new VM(ih, sysCall);
		sysCall.setVM(vm);
		progs = new Programas();
	}

	// ------------------- S I S T E M A - fim
	// --------------------------------------------------------------
	// -------------------------------------------------------------------------------------------------------

	// -------------------------------------------------------------------------------------------------------
	// ------------------- instancia e testa sistema
	public static void main(String args[]) {
		Sistema s = new Sistema();
		// s.loadAndExec(progs.fatorial);
		// s.loadAndExec(progs.fibonacci10, 2);
		s.inicializar();
		// s.loadAndExec(progs.progMinimo);
		// s.loadAndExec(progs.fatorial);
		// s.loadAndExec(progs.fatorialTRAP); // saida
		// Menu menu = new Menu();
		// s.loadAndExec(progs.fibonacciTRAP); // entrada
		// s.loadAndExec(progs.PC); // bubble sort

	}

	public void inicializar() {
		GP gp = new GP();
		Menu m = new Menu();
		m.menu();
	}

	public class Menu {
		public GP gp;
		Scanner scanner;
		
		public Menu() {
			gp = new GP();
			scanner = new Scanner(System.in);
			// new Thread(new MenuThread()).start();
		}

		public void menu() {
				System.out.println("----------------------------------");
				System.out.println("Comandos disponíveis:");
				System.out.println("new - Carregar programa");
				System.out.println("rm - Desalocar programa");
				System.out.println("executa - Executar programa");
				System.out.println("dumpM - Listar processos");
				System.out.println("dump - Exibir conteúdo PCB");
				System.out.println("ps - Listar memória");
				System.out.println("execall - executa todos os processos");
				System.out.println("exit - Sair");
				System.out.println("----------------------------------");
				System.out.print("Digite um comando: ");
				String input = scanner.nextLine().trim();
				String[] parts = input.split("\\s+");
				String command = parts[0].toLowerCase();
				while (true) {
					switch (command) {
						case "new":
							String op;
							System.out.println("Qual programa você deseja carregar?");
							System.out.println("fat - Fatorial");
							System.out.println("fattrap - FatorialTRAP");
							System.out.println("min - Minimo");
							System.out.println("fib10 - Fibonacci10");
							System.out.println("fibtrap - FibonacciTRAP");
							System.out.println("pc - PC");
							System.out.println("pb - PB");
							op = scanner.nextLine();
							switch (op) {
								case "fat":
									gp.criaProcesso(progs.fatorial);
									System.out.println("Programa carregado.");
									break;
								case "fattrap":
									gp.criaProcesso(progs.fatorialTRAP);
									System.out.println("Programa carregado.");
									break;
								case "min":
									gp.criaProcesso(progs.progMinimo);
									System.out.println("Programa carregado.");
									break;
								case "fib10":
									gp.criaProcesso(progs.fibonacci10);
									System.out.println("Programa carregado.");
									break;
								case "fibtrap":
									gp.criaProcesso(progs.fibonacciTRAP);
									System.out.println("Programa carregado.");
									break;
								case "pc":
									gp.criaProcesso(progs.PC);
									System.out.println("Programa carregado.");
									break;
								case "pb":
									gp.criaProcesso(progs.PB);
									System.out.println("Programa carregado.");
									break;
								default:
									System.out.println("Opção inválida.");
									break;
							}
							menu();
							break;
						case "rm":
							int opc;
							System.out.println("Qual programa você deseja desalocar? (id)");
							for (int i = 0; i < gp.filaProcessos.size(); i++) {
								System.out.println(
										"[" + gp.filaProcessos.get(i).id + "] - " + "Frames alocados: "
												+ gp.filaProcessos.get(i).framesAlocados);
							}
							opc = scanner.nextInt();
							scanner.nextLine();
							if (!gp.desalocaProcesso(opc)) {
								System.out.println("Não foi possível desalocar o programa.");
							} else {
								System.out.println("Programa desalocado.");
								vm.mem.dump(0, vm.tamMem);
							}
							menu();
							break;
						case "executa":
							System.out.println("Qual processo você deseja executar?");
							if (gp.filaProcessos.isEmpty()) {
								System.out.println("Sem programas para executar.");
								menu();
								break;
							}
							for (int i = 0; i < gp.filaProcessos.size(); i++) {
								System.out.println(
										"[" + gp.filaProcessos.get(i).id + "] - " + "Frames alocados: "
												+ gp.filaProcessos.get(i).framesAlocados);
							}
							opc = scanner.nextInt();
							scanner.nextLine();
							for (int i = 0; i < gp.filaProcessos.size(); i++) {
								if (gp.filaProcessos.get(i).id == opc) {
									while (gp.filaProcessos.get(i).estado == "PRONTO") {
										gp.setRunning(opc);
										System.out.println("Ponteiro running: " + gp.running);
										vm.cpu.setContext(0, vm.tamMem - 1, 0);
										vm.cpu.run(gp.filaProcessos.get(i));
										gp.setRunning(-1);
									}
									if (!gp.desalocaProcesso(gp.filaProcessos.get(i).id)) {
										System.out.println("Não foi possível desalocar o programa.");
									} else {
										System.out.println("Programa desalocado.");
										vm.mem.dump(0, vm.tamMem);
									}
									break;
								}
							}
							menu();
							break;
						case "ps":
							System.out.println("Lista de processos: ");
							if (gp.filaProcessos.isEmpty()) {
								System.out.println("Sem programas carregados.");
								menu();
								break;
							}
							for (int i = 0; i < gp.filaProcessos.size(); i++) {
								System.out.println("ID PROCESSO: " + gp.filaProcessos.get(i).id);
								System.out.println("ESTADO: " + gp.filaProcessos.get(i).estado);
								System.out.println("PC: " + gp.filaProcessos.get(i).pc);
								System.out.println("FRAMES ALOCADOS: " + gp.filaProcessos.get(i).framesAlocados + "\n");
							}
							menu();
							break;
						case "dump":
							System.out.println("Qual o id do processo desejado?");
							System.out.println("Lista de processos: ");
	
							for (int i = 0; i < gp.filaProcessos.size(); i++) {
								System.out.println(
										"[" + gp.filaProcessos.get(i).id + "] - " + "Frames alocados: "
												+ gp.filaProcessos.get(i).framesAlocados);
	
							}
							opc = scanner.nextInt();
							scanner.nextLine();
							for (int i = 0; i < gp.filaProcessos.size(); i++) {
								if (gp.filaProcessos.get(i).id == opc) {
									System.out.println("ID PROCESSO: " + opc);
									System.out.println("ESTADO: " + gp.filaProcessos.get(i).estado);
									System.out.println("PC: " + gp.filaProcessos.get(i).pc);
									System.out.println("FRAMES ALOCADOS: " + gp.filaProcessos.get(i).framesAlocados);
									break;
								}
							}
							menu();
							break;
						case "dumpm":
							System.out.println("Diga a posição de início: ");
							int ini = scanner.nextInt();
							scanner.nextLine();
							System.out.println("Diga a posição final: ");
							int fim = scanner.nextInt();
							scanner.nextLine();
							if (ini < fim && ini >= 0 && fim >= 1 && ini < 1024 && fim <= 1024)
								vm.mem.dump(ini, fim);
							else
								System.out.println("Posição inválida.");
							menu();
							break;
	
						case "execall":
							while (true) {
								int contFinalizados = 0;
	
								for (int i = 0; i < gp.filaProcessos.size(); i++) {
									PCB pcb = gp.filaProcessos.get(i);
									if (pcb.estado == "PRONTO") {
										System.out.println("Process ID: " + pcb.id);
										System.out.println("Ponteiro running: " + gp.running);
										vm.cpu.setContext(0, vm.tamMem - 1, 0);
										vm.cpu.run(gp.filaProcessos.get(i));
									} else {
										contFinalizados++;
									}
								}
	
								if (contFinalizados == gp.filaProcessos.size()) {
									break;
								}
	
							}
	
							//DESALOCADOR AUTOMATICO
							int[] idsProcessos = new int[gp.filaProcessos.size()];
	
							for(int k = 0; k< gp.filaProcessos.size(); k++){
								idsProcessos[k] = gp.filaProcessos.get(k).id;
							}
	
							for(int j = 0; j < idsProcessos.length; j++){
								gp.desalocaProcesso(idsProcessos[j]);
							}
							
							menu();
							break;
						case "exit":
							System.out.println("Fim do programa!");
							System.exit(0);
							break;
						default:
							System.out.println("Opção inválida.");
							menu();
							break;
					}
				}
			}

		private class MenuThread implements Runnable {

			@Override
			public void run() {
				menu();
			}

		}		
	}

	public class GM {
		public Memory memory;
		public int tamPag;
		public int tamMem;
		public boolean[] frames;
		public int[] tabelaPaginas;
		public ArrayList<Integer> framesAlocados;
		public int nroPaginas;
		public int nroFrames;

		// TABELA DE PÁGINAS

		public GM(Memory memory, int tamMem, int tamPag) {
			this.memory = memory;
			this.tamMem = tamMem;
			this.tamPag = tamPag;

			nroFrames = tamMem / tamPag;
			frames = new boolean[nroFrames];

			for (int i = 0; i < nroFrames; i++) {
				frames[i] = true;
			}
			this.tabelaPaginas = new int[frames.length];
			inicializarTabelaPaginas();
		}

		public void inicializarTabelaPaginas() {
			for (int i = 0; i < tabelaPaginas.length; i++) {
				tabelaPaginas[i] = -1;
			}
		}

		public boolean aloca(int nroPalavras) {
			System.out.println("Tamanho de Pagina: " + tamPag);
			this.framesAlocados = new ArrayList<Integer>();
			this.nroPaginas = nroPalavras / tamPag;
			int framesLivres = 0;

			if (nroPalavras % tamPag > 0) {
				nroPaginas++;
			}

			if(nroPalavras == 7){
				nroPaginas++;
			}

			System.out.println("Número de Páginas: " + nroPaginas);

			for (int i = 0; i < frames.length; i++) {
				if (frames[i] == true) {
					framesLivres++;
				}
			}

			if (framesLivres >= nroPaginas) {
				for (int j = 0; j < nroPaginas; j++) {
					for (int i = 0; i < frames.length; i++) {
						if (frames[i] == true) {
							framesAlocados.add(i);
							tabelaPaginas[i] = j;
							frames[i] = false;
							break;
						}
					}
				}
				return true;
			}

			return false;
		}

		public void desaloca(ArrayList<Integer> framesProcesso) {
			for (int i = 0; i < framesProcesso.size(); i++) {
				int frameDesalocar = framesProcesso.get(i).intValue();
				tabelaPaginas[frameDesalocar] = -1;
				frames[frameDesalocar] = true;
			}
		}
	}

	public class PCB {
		int id;
		String estado;
		int pc;
		ArrayList<Integer> framesAlocados;
		int[] statusReg;
		Word[] programa;

		public PCB(int id, String estado, int pc, ArrayList<Integer> framesAlocados, Word[] programa) {
			this.id = id;
			this.estado = estado;
			this.pc = pc;
			this.framesAlocados = framesAlocados;
			this.programa = programa;
			this.statusReg = new int[10];
		}

	}

	public class GP {
		public int running;
		public List<PCB> filaProcessos;

		public int registro = 0;

		public GP() {
			this.filaProcessos = new ArrayList<PCB>();
		}

		public boolean criaProcesso(Word[] programa) {
			if (vm.gm.aloca(programa.length)) {
				registro++;
				System.out.println("Conseguiu alocar.");
				ArrayList<Integer> paginasAlocadas = vm.gm.framesAlocados;
				PCB proc = new PCB(registro, "PRONTO", 0, paginasAlocadas, programa);
				filaProcessos.add(proc);

				System.out.println("");
				int[] tabelaPaginas = vm.gm.tabelaPaginas;
				System.out.println("-------------Tabela de Páginas--------------");
				for (int i = 0; i < tabelaPaginas.length; i++) {
					int pagina = tabelaPaginas[i];
					if (pagina != -1) {
						System.out
								.println("Página: " + pagina + " Frame: " + i + " Início: "
										+ (i * vm.gm.tamPag)
										+ " Fim: " + (((i * vm.gm.tamPag) - 1) + vm.gm.tamPag));
					}
				}
				System.out.println("--------------------------------------------");

				loadProgram(programa); // carga do programa na memoria
				System.out.println("---------------------------------- programa carregado na memoria");
				vm.mem.dump(0, vm.tamMem); // dump da memoria nestas posicoes

				// s.loadAndExec(proc);
				return true;
			}
			System.out.println("Não conseguiu alocar");
			return false;
		}

		public void setRunning(int run) {
			running = run;
		}

		public boolean desalocaProcesso(int id_processo) {
			PCB pcb = null;
			for (int i = 0; i < filaProcessos.size(); i++) {
				if (filaProcessos.get(i).id == id_processo) {
					pcb = filaProcessos.get(i);
					filaProcessos.remove(i);
					clearMemoria(pcb.programa, pcb.framesAlocados);
					vm.gm.desaloca(pcb.framesAlocados);
					// vm.mem.dump(0, vm.tamMem);
					return true;
				}
			}
			return false;
		}
	}

	// -------------------------------------------------------------------------------------------------------
	// -------------------------------------------------------------------------------------------------------
	// -------------------------------------------------------------------------------------------------------
	// --------------- P R O G R A M A S - não fazem parte do sistema
	// esta classe representa programas armazenados (como se estivessem em disco)
	// que podem ser carregados para a memória (load faz isto)

	public class Programas {
		public Word[] fatorial = new Word[] {
				// este fatorial so aceita valores positivos. nao pode ser zero
				// linha coment
				new Word(Opcode.LDI, 0, -1, 4), // 0 r0 é valor a calcular fatorial
				new Word(Opcode.LDI, 1, -1, 1), // 1 r1 é 1 para multiplicar (por r0)
				new Word(Opcode.LDI, 6, -1, 1), // 2 r6 é 1 para ser o decremento
				new Word(Opcode.LDI, 7, -1, 8), // 3 r7 tem posicao de stop do programa = 8
				new Word(Opcode.JMPIE, 7, 0, 0), // 4 se r0=0 pula para r7(=8)
				new Word(Opcode.MULT, 1, 0, -1), // 5 r1 = r1 * r0
				new Word(Opcode.SUB, 0, 6, -1), // 6 decrementa r0 1
				new Word(Opcode.JMP, -1, -1, 4), // 7 vai p posicao 4
				new Word(Opcode.STD, 1, -1, 10), // 8 coloca valor de r1 na posição 10
				new Word(Opcode.STOP, -1, -1, -1), // 9 stop
				new Word(Opcode.DATA, -1, -1, -1) }; // 10 ao final o valor do fatorial estará na posição 10 da
														// memória

		public Word[] progMinimo = new Word[] {
				new Word(Opcode.LDI, 0, -1, 999),
				new Word(Opcode.STD, 0, -1, 10),
				new Word(Opcode.STD, 0, -1, 11),
				new Word(Opcode.STD, 0, -1, 12),
				new Word(Opcode.STD, 0, -1, 13),
				new Word(Opcode.STD, 0, -1, 14),
				new Word(Opcode.STOP, -1, -1, -1) };

		public Word[] fibonacci10 = new Word[] { // mesmo que prog exemplo, so que usa r0 no lugar de r8
				new Word(Opcode.LDI, 1, -1, 0),
				new Word(Opcode.STD, 1, -1, 20),
				new Word(Opcode.LDI, 2, -1, 1),
				new Word(Opcode.STD, 2, -1, 21),
				new Word(Opcode.LDI, 0, -1, 22),
				new Word(Opcode.LDI, 6, -1, 6),
				new Word(Opcode.LDI, 7, -1, 31),
				new Word(Opcode.LDI, 3, -1, 0),
				new Word(Opcode.ADD, 3, 1, -1),
				new Word(Opcode.LDI, 1, -1, 0),
				new Word(Opcode.ADD, 1, 2, -1),
				new Word(Opcode.ADD, 2, 3, -1),
				new Word(Opcode.STX, 0, 2, -1),
				new Word(Opcode.ADDI, 0, -1, 1),
				new Word(Opcode.SUB, 7, 0, -1),
				new Word(Opcode.JMPIG, 6, 7, -1),
				new Word(Opcode.STOP, -1, -1, -1),
				new Word(Opcode.DATA, -1, -1, -1),
				new Word(Opcode.DATA, -1, -1, -1),
				new Word(Opcode.DATA, -1, -1, -1),
				new Word(Opcode.DATA, -1, -1, -1), // POS 20
				new Word(Opcode.DATA, -1, -1, -1),
				new Word(Opcode.DATA, -1, -1, -1),
				new Word(Opcode.DATA, -1, -1, -1),
				new Word(Opcode.DATA, -1, -1, -1),
				new Word(Opcode.DATA, -1, -1, -1),
				new Word(Opcode.DATA, -1, -1, -1),
				new Word(Opcode.DATA, -1, -1, -1),
				new Word(Opcode.DATA, -1, -1, -1),
				new Word(Opcode.DATA, -1, -1, -1) }; // ate aqui - serie de fibonacci ficara armazenada

		public Word[] fatorialTRAP = new Word[] {
				new Word(Opcode.LDI, 0, -1, 7), // numero para colocar na memoria
				new Word(Opcode.STD, 0, -1, 50),
				new Word(Opcode.LDD, 0, -1, 50),
				new Word(Opcode.LDI, 1, -1, -1),
				new Word(Opcode.LDI, 2, -1, 13), // SALVAR POS STOP
				new Word(Opcode.JMPIL, 2, 0, -1), // caso negativo pula pro STD
				new Word(Opcode.LDI, 1, -1, 1),
				new Word(Opcode.LDI, 6, -1, 1),
				new Word(Opcode.LDI, 7, -1, 13),
				new Word(Opcode.JMPIE, 7, 0, 0), // POS 9 pula pra STD (Stop-1)
				new Word(Opcode.MULT, 1, 0, -1),
				new Word(Opcode.SUB, 0, 6, -1),
				new Word(Opcode.JMP, -1, -1, 9), // pula para o JMPIE
				new Word(Opcode.STD, 1, -1, 18),
				new Word(Opcode.LDI, 8, -1, 2), // escrita
				new Word(Opcode.LDI, 9, -1, 18), // endereco com valor a escrever
				new Word(Opcode.TRAP, -1, -1, -1),
				new Word(Opcode.STOP, -1, -1, -1), // POS 17
				new Word(Opcode.DATA, -1, -1, -1) };// POS 18

		public Word[] fibonacciTRAP = new Word[] { // mesmo que prog exemplo, so que usa r0 no lugar de r8
				new Word(Opcode.LDI, 8, -1, 1), // leitura
				new Word(Opcode.LDI, 9, -1, 100), // endereco a guardar
				new Word(Opcode.TRAP, -1, -1, -1),
				new Word(Opcode.LDD, 7, -1, 100), // numero do tamanho do fib
				new Word(Opcode.LDI, 3, -1, 0),
				new Word(Opcode.ADD, 3, 7, -1),
				new Word(Opcode.LDI, 4, -1, 36), // posicao para qual ira pular (stop) *
				new Word(Opcode.LDI, 1, -1, -1), // caso negativo
				new Word(Opcode.STD, 1, -1, 41),
				new Word(Opcode.JMPIL, 4, 7, -1), // pula pra stop caso negativo *
				new Word(Opcode.JMPIE, 4, 7, -1), // pula pra stop caso 0
				new Word(Opcode.ADDI, 7, -1, 41), // fibonacci + posição do stop
				new Word(Opcode.LDI, 1, -1, 0),
				new Word(Opcode.STD, 1, -1, 41), // 25 posicao de memoria onde inicia a serie de fibonacci gerada
				new Word(Opcode.SUBI, 3, -1, 1), // se 1 pula pro stop
				new Word(Opcode.JMPIE, 4, 3, -1),
				new Word(Opcode.ADDI, 3, -1, 1),
				new Word(Opcode.LDI, 2, -1, 1),
				new Word(Opcode.STD, 2, -1, 42),
				new Word(Opcode.SUBI, 3, -1, 2), // se 2 pula pro stop
				new Word(Opcode.JMPIE, 4, 3, -1),
				new Word(Opcode.LDI, 0, -1, 43),
				new Word(Opcode.LDI, 6, -1, 25), // salva posição de retorno do loop
				new Word(Opcode.LDI, 5, -1, 0), // salva tamanho
				new Word(Opcode.ADD, 5, 7, -1),
				new Word(Opcode.LDI, 7, -1, 0), // zera (inicio do loop)
				new Word(Opcode.ADD, 7, 5, -1), // recarrega tamanho
				new Word(Opcode.LDI, 3, -1, 0),
				new Word(Opcode.ADD, 3, 1, -1),
				new Word(Opcode.LDI, 1, -1, 0),
				new Word(Opcode.ADD, 1, 2, -1),
				new Word(Opcode.ADD, 2, 3, -1),
				new Word(Opcode.STX, 0, 2, -1),
				new Word(Opcode.ADDI, 0, -1, 1),
				new Word(Opcode.SUB, 7, 0, -1),
				new Word(Opcode.JMPIG, 6, 7, -1), // volta para o inicio do loop
				new Word(Opcode.STOP, -1, -1, -1), // POS 36
				new Word(Opcode.DATA, -1, -1, -1),
				new Word(Opcode.DATA, -1, -1, -1),
				new Word(Opcode.DATA, -1, -1, -1),
				new Word(Opcode.DATA, -1, -1, -1),
				new Word(Opcode.DATA, -1, -1, -1), // POS 41
				new Word(Opcode.DATA, -1, -1, -1),
				new Word(Opcode.DATA, -1, -1, -1),
				new Word(Opcode.DATA, -1, -1, -1),
				new Word(Opcode.DATA, -1, -1, -1),
				new Word(Opcode.DATA, -1, -1, -1),
				new Word(Opcode.DATA, -1, -1, -1),
				new Word(Opcode.DATA, -1, -1, -1),
				new Word(Opcode.DATA, -1, -1, -1),
				new Word(Opcode.DATA, -1, -1, -1),
				new Word(Opcode.DATA, -1, -1, -1),
				new Word(Opcode.DATA, -1, -1, -1),
				new Word(Opcode.DATA, -1, -1, -1),
				new Word(Opcode.DATA, -1, -1, -1),
				new Word(Opcode.DATA, -1, -1, -1)
		};

		public Word[] PB = new Word[] {
				// dado um inteiro em alguma posição de memória,
				// se for negativo armazena -1 na saída; se for positivo responde o fatorial do
				// número na saída
				new Word(Opcode.LDI, 0, -1, 7), // numero para colocar na memoria
				new Word(Opcode.STD, 0, -1, 50),
				new Word(Opcode.LDD, 0, -1, 50),
				new Word(Opcode.LDI, 1, -1, -1),
				new Word(Opcode.LDI, 2, -1, 13), // SALVAR POS STOP
				new Word(Opcode.JMPIL, 2, 0, -1), // caso negativo pula pro STD
				new Word(Opcode.LDI, 1, -1, 1),
				new Word(Opcode.LDI, 6, -1, 1),
				new Word(Opcode.LDI, 7, -1, 13),
				new Word(Opcode.JMPIE, 7, 0, 0), // POS 9 pula pra STD (Stop-1)
				new Word(Opcode.MULT, 1, 0, -1),
				new Word(Opcode.SUB, 0, 6, -1),
				new Word(Opcode.JMP, -1, -1, 9), // pula para o JMPIE
				new Word(Opcode.STD, 1, -1, 15),
				new Word(Opcode.STOP, -1, -1, -1), // POS 14
				new Word(Opcode.DATA, -1, -1, -1) }; // POS 15

		public Word[] PC = new Word[] {
				// Para um N definido (10 por exemplo)
				// o programa ordena um vetor de N números em alguma posição de memória;
				// ordena usando bubble sort
				// loop ate que não swap nada
				// passando pelos N valores
				// faz swap de vizinhos se da esquerda maior que da direita
				new Word(Opcode.LDI, 7, -1, 5), // TAMANHO DO BUBBLE SORT (N)
				new Word(Opcode.LDI, 6, -1, 5), // aux N
				new Word(Opcode.LDI, 5, -1, 46), // LOCAL DA MEMORIA
				new Word(Opcode.LDI, 4, -1, 47), // aux local memoria
				new Word(Opcode.LDI, 0, -1, 4), // colocando valores na memoria
				new Word(Opcode.STD, 0, -1, 46),
				new Word(Opcode.LDI, 0, -1, 3),
				new Word(Opcode.STD, 0, -1, 47),
				new Word(Opcode.LDI, 0, -1, 5),
				new Word(Opcode.STD, 0, -1, 48),
				new Word(Opcode.LDI, 0, -1, 1),
				new Word(Opcode.STD, 0, -1, 49),
				new Word(Opcode.LDI, 0, -1, 2),
				new Word(Opcode.STD, 0, -1, 50), // colocando valores na memoria até aqui - POS 13
				new Word(Opcode.LDI, 3, -1, 25), // Posicao para pulo CHAVE 1
				new Word(Opcode.STD, 3, -1, 99),
				new Word(Opcode.LDI, 3, -1, 22), // Posicao para pulo CHAVE 2
				new Word(Opcode.STD, 3, -1, 98),
				new Word(Opcode.LDI, 3, -1, 38), // Posicao para pulo CHAVE 3
				new Word(Opcode.STD, 3, -1, 97),
				new Word(Opcode.LDI, 3, -1, 25), // Posicao para pulo CHAVE 4 (não usada)
				new Word(Opcode.STD, 3, -1, 96),
				new Word(Opcode.LDI, 6, -1, 0), // r6 = r7 - 1 POS 22
				new Word(Opcode.ADD, 6, 7, -1),
				new Word(Opcode.SUBI, 6, -1, 1), // ate aqui
				new Word(Opcode.JMPIEM, -1, 6, 97), // CHAVE 3 para pular quando r7 for 1 e r6 0 para interomper o
													// loop
													// de vez do programa
				new Word(Opcode.LDX, 0, 5, -1), // r0 e r1 pegando valores das posições da memoria POS 26
				new Word(Opcode.LDX, 1, 4, -1),
				new Word(Opcode.LDI, 2, -1, 0),
				new Word(Opcode.ADD, 2, 0, -1),
				new Word(Opcode.SUB, 2, 1, -1),
				new Word(Opcode.ADDI, 4, -1, 1),
				new Word(Opcode.SUBI, 6, -1, 1),
				new Word(Opcode.JMPILM, -1, 2, 99), // LOOP chave 1 caso neg procura prox
				new Word(Opcode.STX, 5, 1, -1),
				new Word(Opcode.SUBI, 4, -1, 1),
				new Word(Opcode.STX, 4, 0, -1),
				new Word(Opcode.ADDI, 4, -1, 1),
				new Word(Opcode.JMPIGM, -1, 6, 99), // LOOP chave 1 POS 38
				new Word(Opcode.ADDI, 5, -1, 1),
				new Word(Opcode.SUBI, 7, -1, 1),
				new Word(Opcode.LDI, 4, -1, 0), // r4 = r5 + 1 POS 41
				new Word(Opcode.ADD, 4, 5, -1),
				new Word(Opcode.ADDI, 4, -1, 1), // ate aqui
				new Word(Opcode.JMPIGM, -1, 7, 98), // LOOP chave 2
				new Word(Opcode.STOP, -1, -1, -1), // POS 45
				new Word(Opcode.DATA, -1, -1, -1),
				new Word(Opcode.DATA, -1, -1, -1),
				new Word(Opcode.DATA, -1, -1, -1),
				new Word(Opcode.DATA, -1, -1, -1),
				new Word(Opcode.DATA, -1, -1, -1),
				new Word(Opcode.DATA, -1, -1, -1),
				new Word(Opcode.DATA, -1, -1, -1),
				new Word(Opcode.DATA, -1, -1, -1) };
	}

}
